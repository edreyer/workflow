package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A callable application use case: public input [I] in, `Either` of public error [E] or output [O] out.
 */
public fun interface UseCase<in I, out E, out O> {
  /** Runs the use case with request metadata such as a correlation ID. */
  public suspend operator fun invoke(input: I, context: WorkflowContext): Either<E, O>

  /** Runs the use case with an empty [WorkflowContext]. */
  public suspend operator fun invoke(input: I): Either<E, O> = invoke(input, WorkflowContext())
}

/**
 * Builds a use case that returns raw [WorkflowError]s.
 *
 * ```kotlin
 * val placeOrder: UseCase<PlaceOrder, WorkflowError, OrderDto> =
 *   useCase("PlaceOrder") {
 *     startWith { command -> OrderDraft(command.id, command.items) }
 *       .then(validateOrder)
 *       .then(priceOrder)
 *       .result { order -> order.toDto() }
 *   }
 * ```
 *
 * @param name Stable operational name used in logs and summaries. Do not include user or request data.
 * @param config Logging and summary settings, usually one [UseCaseConfig] shared by the application.
 */
public fun <I, O> useCase(
  name: String,
  config: UseCaseConfig = UseCaseConfig.Default,
  configure: UseCaseBuilder<I, WorkflowError>.() -> UseCaseDefinition<I, WorkflowError, *, O>,
): UseCase<I, WorkflowError, O> = useCase(name, { it }, config, configure)

/**
 * Builds a use case that maps [WorkflowError]s to the application's error type [E].
 *
 * The [errors] mapper should be total and non-throwing. It receives the error exactly as it was
 * raised, including application-defined [WorkflowError] types.
 *
 * @param name Stable operational name used in logs and summaries. Do not include user or request data.
 * @param config Logging and summary settings, usually one [UseCaseConfig] shared by the application.
 */
public fun <I, E, O> useCase(
  name: String,
  errors: (WorkflowError) -> E,
  config: UseCaseConfig = UseCaseConfig.Default,
  configure: UseCaseBuilder<I, E>.() -> UseCaseDefinition<I, E, *, O>,
): UseCase<I, E, O> {
  val definition = UseCaseBuilder<I, E>().configure()
  return UseCaseRunner(name, errors, config, definition)
}

/**
 * Receiver of the `useCase { ... }` block. Start the pipeline with [startWith].
 */
@WorkflowDsl
public class UseCaseBuilder<I, E> internal constructor() {
  /**
   * Converts public input into the first workflow state. The block is an Arrow [Raise], so
   * validate with `ensure` or `ensureNotNull`. Thrown exceptions become [WorkflowError.ExceptionError].
   * Returning an `Either` instead of calling `bind()` on it fails with [WorkflowError.CompositionError].
   */
  public fun <S> startWith(block: suspend Raise<WorkflowError>.(input: I) -> S): Pipeline<I, E, S> {
    val start: suspend Raise<WorkflowError>.(Any?) -> Any? = { input ->
      @Suppress("UNCHECKED_CAST")
      val state = block(input as I)
      ensure(state !is Either<*, *>) { WorkflowError.CompositionError("startWith returned an Either. Call .bind() on it.") }
      state
    }
    return Pipeline(start, emptyList())
  }
}

/**
 * An immutable, typed chain of workflow steps whose current state type is [S].
 *
 * Every method returns a new pipeline, so a step whose input does not accept [S] is a compile error.
 * Finish with [result].
 */
public class Pipeline<I, E, S> internal constructor(
  internal val start: suspend Raise<WorkflowError>.(Any?) -> Any?,
  internal val steps: List<PipelineStep>,
) {
  private fun <N> add(step: PipelineStep): Pipeline<I, E, N> = Pipeline(start, steps + step)

  /** Adds a join; branches that succeeded are kept in the run even when another branch fails. */
  private fun <N> join(workflow: Workflow<S, N>): Pipeline<I, E, N> = add(SequentialStep(workflow, keepPartialResults = true))

  /** Runs [workflow] on the current state; its output becomes the new state. */
  public fun <O> then(workflow: Workflow<S, O>): Pipeline<I, E, O> = add(SequentialStep(workflow))

  /**
   * Runs [step] on the current state; its output becomes the new state. The block is a
   * [WorkflowStepScope], so it can fail with `ensure` or `raise`, call functions that declare
   * `context(_: Raise<...>)`, and `emit` events.
   *
   * ```kotlin
   * .then("capture-payment") { order -> payments.capture(order) }
   * ```
   *
   * @param id Stable identifier used in execution history, logs, and summaries.
   */
  public fun <O> then(id: String, step: suspend WorkflowStepScope.(state: S) -> O): Pipeline<I, E, O> =
    then(workflow(id, step))

  /**
   * Checks the current state and passes it on unchanged, such as an authorization check. Fail with
   * `ensure` or `raise`.
   *
   * @param id Stable identifier used in execution history, logs, and summaries.
   */
  public fun guard(id: String, check: suspend WorkflowStepScope.(state: S) -> Unit): Pipeline<I, E, S> =
    then(io.liquidsoftware.workflow.guard(id, check))

  /**
   * Runs [workflow] only when [predicate] matches the current state. Because the step may be
   * skipped, it must keep the state type. The predicate's receiver gives access to the events and
   * context so far.
   */
  public fun thenIf(
    workflow: Workflow<S, S>,
    predicate: WorkflowResult<S>.(state: S) -> Boolean,
  ): Pipeline<I, E, S> {
    @Suppress("UNCHECKED_CAST")
    val untyped = predicate as WorkflowResult<Any?>.(Any?) -> Boolean
    return add(ConditionalStep(workflow, untyped))
  }

  /**
   * Runs side-effect [workflows] concurrently on the current state and keeps that state. Their
   * outputs are ignored; their events and context are merged.
   */
  public fun parallel(
    vararg workflows: Workflow<S, Any?>,
    policy: ParallelErrorPolicy = ParallelErrorPolicy.WaitAll,
  ): Pipeline<I, E, S> = add(ParallelSideEffectStep(workflows.toList(), policy))

  /**
   * Starts a side-effect [workflow] without waiting for it. Its events and failures are merged at the
   * next [awaitLaunched], or when the pipeline finishes. A failure or [timeout] never fails the use
   * case; it is reported as a [LaunchedFailureEvent] and in [WorkflowContext.LAUNCHED_FAILURES].
   */
  public fun thenLaunch(workflow: Workflow<S, Any?>, timeout: Duration? = null): Pipeline<I, E, S> =
    add(LaunchStep(workflow, timeout))

  /** Waits for launched side effects and merges their events and context. */
  public fun awaitLaunched(): Pipeline<I, E, S> = add(AwaitLaunchedStep)

  /** Runs 2 workflows concurrently on the current state and merges their outputs into the next state. The current state is the receiver of [merge]. */
  public fun <T1, T2, R> parallelJoin(
    a: Workflow<S, T1>,
    b: Workflow<S, T2>,
    policy: ParallelErrorPolicy = ParallelErrorPolicy.WaitAll,
    merge: S.(T1, T2) -> R,
  ): Pipeline<I, E, R> = join(io.liquidsoftware.workflow.parallelJoin(a, b, policy = policy, merge = merge))

  /** Runs 3 workflows concurrently on the current state and merges their outputs into the next state. The current state is the receiver of [merge]. */
  public fun <T1, T2, T3, R> parallelJoin(
    a: Workflow<S, T1>,
    b: Workflow<S, T2>,
    c: Workflow<S, T3>,
    policy: ParallelErrorPolicy = ParallelErrorPolicy.WaitAll,
    merge: S.(T1, T2, T3) -> R,
  ): Pipeline<I, E, R> = join(io.liquidsoftware.workflow.parallelJoin(a, b, c, policy = policy, merge = merge))

  /** Runs 4 workflows concurrently on the current state and merges their outputs into the next state. The current state is the receiver of [merge]. */
  public fun <T1, T2, T3, T4, R> parallelJoin(
    a: Workflow<S, T1>,
    b: Workflow<S, T2>,
    c: Workflow<S, T3>,
    d: Workflow<S, T4>,
    policy: ParallelErrorPolicy = ParallelErrorPolicy.WaitAll,
    merge: S.(T1, T2, T3, T4) -> R,
  ): Pipeline<I, E, R> = join(io.liquidsoftware.workflow.parallelJoin(a, b, c, d, policy = policy, merge = merge))

  /** Runs 5 workflows concurrently on the current state and merges their outputs into the next state. The current state is the receiver of [merge]. */
  public fun <T1, T2, T3, T4, T5, R> parallelJoin(
    a: Workflow<S, T1>,
    b: Workflow<S, T2>,
    c: Workflow<S, T3>,
    d: Workflow<S, T4>,
    e: Workflow<S, T5>,
    policy: ParallelErrorPolicy = ParallelErrorPolicy.WaitAll,
    merge: S.(T1, T2, T3, T4, T5) -> R,
  ): Pipeline<I, E, R> = join(io.liquidsoftware.workflow.parallelJoin(a, b, c, d, e, policy = policy, merge = merge))

  /** Runs 6 workflows concurrently on the current state and merges their outputs into the next state. The current state is the receiver of [merge]. */
  public fun <T1, T2, T3, T4, T5, T6, R> parallelJoin(
    a: Workflow<S, T1>,
    b: Workflow<S, T2>,
    c: Workflow<S, T3>,
    d: Workflow<S, T4>,
    e: Workflow<S, T5>,
    f: Workflow<S, T6>,
    policy: ParallelErrorPolicy = ParallelErrorPolicy.WaitAll,
    merge: S.(T1, T2, T3, T4, T5, T6) -> R,
  ): Pipeline<I, E, R> = join(io.liquidsoftware.workflow.parallelJoin(a, b, c, d, e, f, policy = policy, merge = merge))

  /** Runs 7 workflows concurrently on the current state and merges their outputs into the next state. The current state is the receiver of [merge]. */
  public fun <T1, T2, T3, T4, T5, T6, T7, R> parallelJoin(
    a: Workflow<S, T1>,
    b: Workflow<S, T2>,
    c: Workflow<S, T3>,
    d: Workflow<S, T4>,
    e: Workflow<S, T5>,
    f: Workflow<S, T6>,
    g: Workflow<S, T7>,
    policy: ParallelErrorPolicy = ParallelErrorPolicy.WaitAll,
    merge: S.(T1, T2, T3, T4, T5, T6, T7) -> R,
  ): Pipeline<I, E, R> = join(io.liquidsoftware.workflow.parallelJoin(a, b, c, d, e, f, g, policy = policy, merge = merge))

  /** Runs 8 workflows concurrently on the current state and merges their outputs into the next state. The current state is the receiver of [merge]. */
  public fun <T1, T2, T3, T4, T5, T6, T7, T8, R> parallelJoin(
    a: Workflow<S, T1>,
    b: Workflow<S, T2>,
    c: Workflow<S, T3>,
    d: Workflow<S, T4>,
    e: Workflow<S, T5>,
    f: Workflow<S, T6>,
    g: Workflow<S, T7>,
    h: Workflow<S, T8>,
    policy: ParallelErrorPolicy = ParallelErrorPolicy.WaitAll,
    merge: S.(T1, T2, T3, T4, T5, T6, T7, T8) -> R,
  ): Pipeline<I, E, R> = join(io.liquidsoftware.workflow.parallelJoin(a, b, c, d, e, f, g, h, policy = policy, merge = merge))

  /**
   * Projects the final state into the public output. The block is an Arrow [Raise], and its
   * receiver also exposes the events and context collected by the pipeline.
   */
  public fun <O> result(project: suspend ResultScope<S>.(state: S) -> O): UseCaseDefinition<I, E, S, O> =
    UseCaseDefinition(this, project, UseCaseLogging())
}

/**
 * Receiver of [Pipeline.result]: an Arrow [Raise] with access to the use case's events and context.
 */
@WorkflowDsl
public class ResultScope<out S> internal constructor(
  raise: Raise<WorkflowError>,
  /** The final state, events, and context of the pipeline. */
  public val result: WorkflowResult<S>,
) : Raise<WorkflowError> by raise {
  /** Every event emitted by the pipeline, in order. */
  public val events: List<Event> get() = result.events

  /** The final context. */
  public val context: WorkflowContext get() = result.context

  /**
   * Returns the last event of type [T], or raises [WorkflowError.CompositionError] when none was emitted.
   */
  public inline fun <reified T : Event> requireEvent(): T =
    events.lastEvent<T>() ?: raise(WorkflowError.CompositionError("Expected event ${T::class.simpleName} was not emitted"))
}

/**
 * A complete use case definition. Optionally add [logging] fields.
 */
public class UseCaseDefinition<I, E, S, O> internal constructor(
  internal val pipeline: Pipeline<I, E, S>,
  internal val project: suspend ResultScope<S>.(S) -> O,
  internal val logging: UseCaseLogging<E, S>,
) {
  /**
   * Adds explicit fields to the debug "finished" log line. Fields are only collected when debug
   * logging is enabled, and a failure while collecting them never changes the use case result.
   */
  public fun logging(configure: UseCaseLoggingBuilder<E, S>.() -> Unit): UseCaseDefinition<I, E, S, O> =
    UseCaseDefinition(pipeline, project, UseCaseLoggingBuilder(logging).apply(configure).build())
}

/**
 * Emitted when a launched side effect fails or times out.
 */
public data class LaunchedFailureEvent(
  val workflowId: String,
  val message: String,
) : Event

// ---------------------------------------------------------------------------------------------
// Execution
// ---------------------------------------------------------------------------------------------

/** Mutable state of one use case run. Steps run one at a time, so it needs no locking. */
internal class PipelineRun(
  val coroutineScope: CoroutineScope,
  val recorder: ExecutionRecorder,
  var context: WorkflowContext,
) {
  val events = mutableListOf<Event>()
  val launched = mutableListOf<LaunchedJob>()

  fun absorb(result: WorkflowResult<*>) {
    events += result.events
  }

  fun snapshot(state: Any?): WorkflowResult<Any?> = WorkflowResult(state, events.toList(), context)
}

internal class LaunchedJob(
  val workflowId: String,
  val deferred: Deferred<Either<WorkflowError, WorkflowResult<Any?>>>,
)

internal interface PipelineStep {
  suspend fun Raise<WorkflowError>.execute(state: Any?, run: PipelineRun): Any?
}

@Suppress("UNCHECKED_CAST")
private fun Workflow<*, *>.untyped(): Workflow<Any?, Any?> = this as Workflow<Any?, Any?>

private class SequentialStep(
  workflow: Workflow<*, *>,
  private val keepPartialResults: Boolean = false,
) : PipelineStep {
  private val workflow = workflow.untyped()

  override suspend fun Raise<WorkflowError>.execute(state: Any?, run: PipelineRun): Any? {
    val onFailure: ((WorkflowResult<Nothing?>) -> Unit)? = if (keepPartialResults) run::absorb else null
    val result = workflow.runStep(state, run.context, run.recorder, onFailure).bind()
    run.absorb(result)
    return result.state
  }
}

private class ConditionalStep(
  workflow: Workflow<*, *>,
  private val predicate: WorkflowResult<Any?>.(Any?) -> Boolean,
) : PipelineStep {
  private val workflow = workflow.untyped()

  override suspend fun Raise<WorkflowError>.execute(state: Any?, run: PipelineRun): Any? {
    val matches = catchingExceptions { run.snapshot(state).predicate(state) }
    if (!matches) return state
    val result = workflow.runStep(state, run.context, run.recorder).bind()
    run.absorb(result)
    return result.state
  }
}

private class ParallelSideEffectStep(
  workflows: List<Workflow<*, *>>,
  private val policy: ParallelErrorPolicy,
) : PipelineStep {
  private val workflows = workflows.map { it.untyped() }

  override suspend fun Raise<WorkflowError>.execute(state: Any?, run: PipelineRun): Any? {
    runBranches(workflows, state, run.context, run.recorder, policy, run::absorb).bind()
    return state
  }
}

private class LaunchStep(
  workflow: Workflow<*, *>,
  private val timeout: Duration?,
) : PipelineStep {
  private val workflow = workflow.untyped()

  override suspend fun Raise<WorkflowError>.execute(state: Any?, run: PipelineRun): Any? {
    val context = run.context
    val recorder = run.recorder.forLaunched()
    val deferred = run.coroutineScope.async {
      if (timeout == null) {
        workflow.runStep(state, context, recorder)
      } else {
        val startTime = Instant.now()
        withTimeoutOrNull(timeout) { workflow.runStep(state, context, recorder) }
          ?: timedOut(startTime, timeout, recorder)
      }
    }
    run.launched += LaunchedJob(workflow.id, deferred)
    return state
  }

  /** Records the timed-out run, which was cancelled before it could record itself. */
  private fun timedOut(startTime: Instant, timeout: Duration, recorder: ExecutionRecorder): Either<WorkflowError, Nothing> {
    val error = WorkflowError.ExecutionError("thenLaunch timeout: ${workflow.id}")
    val execution = WorkflowExecution(
      workflowName = workflow.id,
      workflowId = workflow.id,
      startTime = startTime,
      endTime = startTime.plusNanos(timeout.inWholeNanoseconds),
      duration = timeout,
      succeeded = false,
    )
    recorder.record(execution, error)
    return Either.Left(error)
  }
}

private data object AwaitLaunchedStep : PipelineStep {
  override suspend fun Raise<WorkflowError>.execute(state: Any?, run: PipelineRun): Any? {
    drainLaunched(run)
    return state
  }
}

private suspend fun drainLaunched(run: PipelineRun) {
  if (run.launched.isEmpty()) return
  val pending = run.launched.toList()
  run.launched.clear()
  val outcomes = pending.map { it.deferred }.awaitAll()
  val failures = mutableListOf<String>()
  pending.zip(outcomes).forEach { (job, outcome) ->
    when (outcome) {
      is Either.Right -> run.absorb(outcome.value)
      is Either.Left -> {
        val message = outcome.value.message
        run.events += LaunchedFailureEvent(job.workflowId, message)
        failures += "thenLaunch failure ${job.workflowId} ($message)"
      }
    }
  }
  if (failures.isNotEmpty()) {
    val previous = run.context[WorkflowContext.LAUNCHED_FAILURES].orEmpty()
    run.context = run.context.with(WorkflowContext.LAUNCHED_FAILURES, previous + failures)
  }
}

internal class UseCaseRunner<I, E, S, O>(
  private val name: String,
  private val errors: (WorkflowError) -> E,
  private val config: UseCaseConfig,
  private val definition: UseCaseDefinition<I, E, S, O>,
) : UseCase<I, E, O> {

  override suspend fun invoke(input: I, context: WorkflowContext): Either<E, O> {
    val mark = TimeSource.Monotonic.markNow()
    logUseCaseStarted(name)
    val recorder = ExecutionRecorder()
    var finalResult: WorkflowResult<Any?>? = null
    var runState: PipelineRun? = null

    val outcome: Either<WorkflowError, O> = coroutineScope {
      val pipelineRun = PipelineRun(this, recorder, context).also { runState = it }
      either {
        val pipeline = definition.pipeline
        var state = catchingExceptions { pipeline.start(this, input) }
        for (step in pipeline.steps) {
          state = with(step) { execute(state, pipelineRun) }
        }
        drainLaunched(pipelineRun)
        val result = pipelineRun.snapshot(state)
        finalResult = result
        @Suppress("UNCHECKED_CAST")
        val typed = result as WorkflowResult<S>
        catchingExceptions { definition.project(ResultScope(this, typed), typed.state) }
      }
    }

    // On failure, report what the run had collected: launched work has finished by now (the
    // coroutine scope waited for it), so merging it does not suspend.
    val summaryResult = finalResult ?: runState?.let { run ->
      drainLaunched(run)
      run.snapshot(null)
    }
    val summary = buildSummary(name, outcome, summaryResult, recorder, context, mark.elapsedNow())
    val metadata = UseCaseLogMetadata(summary.eventsCount, summary.executions.size)

    val mapped: Either<E, O> = try {
      outcome.mapLeft(errors)
    } catch (ex: Throwable) {
      logUseCaseFinished(config, name, succeeded = false, summary.duration, metadata)
      notifySummaryListeners(config, summary)
      throw ex
    }

    logUseCaseFinished(config, name, mapped.isRight(), summary.duration, metadata) {
      @Suppress("UNCHECKED_CAST")
      definition.logging.fieldsFor(name, finalResult as WorkflowResult<S>?, mapped)
    }
    notifySummaryListeners(config, summary)
    return mapped
  }
}
