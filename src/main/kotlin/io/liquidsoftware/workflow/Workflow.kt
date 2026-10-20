package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.Some
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.resilience.Schedule
import arrow.resilience.retry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Marks the receivers of the workflow DSL so a nested block cannot silently call a member of an
 * enclosing one.
 */
@DslMarker
@Target(AnnotationTarget.CLASS)
public annotation class WorkflowDsl

/**
 * One named step of a use case: it turns input state [I] into output state [O], and can fail with an
 * error of type [E].
 *
 * Steps are written as functions, not classes. Define one inline with `Pipeline.then(id) { }`, or
 * as a reusable value with the [workflow] function. The step block runs inside a
 * [WorkflowStepScope], which is an Arrow [Raise] for [E]. Fail with `ensure`, `ensureNotNull`,
 * `raise`, or `bind()`, and record events with [WorkflowStepScope.emit]. Thrown exceptions become
 * [Failure.Unexpected].
 *
 * A step that never raises has error type `Nothing` and fits any pipeline. A step raising a
 * narrower error type, such as one case of a sealed hierarchy, fits a pipeline for the whole
 * hierarchy. Adapt a step written for an unrelated error type with [mapError].
 *
 * Domain code does not need this library. A function that declares `context(_: Raise<OrderError>)`
 * can be called from any step block whose error type is `OrderError`:
 *
 * ```kotlin
 * class Inventory {
 *   context(_: Raise<OrderError>)
 *   suspend fun reserve(order: ValidatedOrder): ReservedOrder = ...
 * }
 *
 * val reserveInventory = workflow<ValidatedOrder, ReservedOrder, OrderError>("reserve-inventory") { order ->
 *   inventory.reserve(order)
 * }
 * ```
 */
public class Workflow<in I, out O, out E> internal constructor(
  /** Stable identifier used in execution history, logs, and summaries. */
  public val id: String,
  private val block: suspend WorkflowStepScope<E>.(input: I) -> O,
) {
  internal suspend fun WorkflowStepScope<E>.execute(input: I): O = block(input)

  override fun toString(): String = "Workflow($id)"
}

/**
 * Defines a reusable [Workflow]. For a step used in one pipeline only, prefer `Pipeline.then(id) { }`,
 * which infers every type.
 *
 * ```kotlin
 * val validateOrder = workflow<OrderDraft, ValidatedOrder, OrderError>("validate-order") { draft ->
 *   ensure(draft.items.isNotEmpty()) { OrderError.EmptyOrder }
 *   emit(OrderValidated(draft.orderId))
 *   ValidatedOrder(draft.orderId, draft.items)
 * }
 * ```
 *
 * @param id Stable identifier used in execution history, logs, and summaries.
 */
public fun <I, O, E> workflow(
  id: String,
  block: suspend WorkflowStepScope<E>.(input: I) -> O,
): Workflow<I, O, E> = Workflow(id, block)

/**
 * Defines a [Workflow] that checks its input and passes it on unchanged, such as an authorization
 * check. Fail with `ensure` or `raise`.
 *
 * ```kotlin
 * val authorize = guard<PlaceOrder, OrderError>("authorize") { command ->
 *   ensure(command.customer.canOrder) { OrderError.NotAllowed }
 * }
 * ```
 *
 * @param id Stable identifier used in execution history, logs, and summaries.
 */
public fun <I, E> guard(
  id: String,
  block: suspend WorkflowStepScope<E>.(input: I) -> Unit,
): Workflow<I, I, E> = Workflow(id) { input -> block(input); input }

/**
 * Returns a step that runs this one and converts the errors it raises with [transform]. Use it to
 * add a step written for a different error type to a pipeline.
 *
 * ```kotlin
 * .then(chargeCard.mapError { e: PaymentError -> OrderError.PaymentDeclined(e.reason) })
 * ```
 */
public fun <I, O, E, F> Workflow<I, O, E>.mapError(transform: (E) -> F): Workflow<I, O, F> {
  val original = this
  return Workflow(id) { input ->
    val scope = this
    with(original) { scope.withRaisedErrors<E> { error -> scope.raise(transform(error)) }.execute(input) }
  }
}

/**
 * Returns a step that converts its input with [transform] before running this one, so a step written
 * for one type fits a pipeline whose state is another. The step keeps its id.
 *
 * ```kotlin
 * .thenLaunch(sendReceipt.mapInput { order: PaidOrder -> order.customer })
 * ```
 */
public fun <I, J, O, E> Workflow<I, O, E>.mapInput(transform: (J) -> I): Workflow<J, O, E> {
  val original = this
  return Workflow(id) { input -> runInside(original, transform(input)) }
}

/**
 * Returns a step that runs this one and converts its output with [transform]. The step keeps its id.
 */
public fun <I, O, P, E> Workflow<I, O, E>.mapOutput(transform: (O) -> P): Workflow<I, P, E> {
  val original = this
  return Workflow(id) { input -> transform(runInside(original, input)) }
}

/**
 * Returns a step that fails with [error] when this one takes longer than [duration]. The step keeps
 * its id, and the timeout is a raised error the use case's `errors` mapper can handle, not a
 * [Failure.Unexpected].
 *
 * ```kotlin
 * val chargeCard = workflow<PricedOrder, PaidOrder, PaymentError>("charge-card") { payments.capture(it) }
 *   .timeout(2.seconds) { PaymentError.TimedOut }
 * ```
 *
 * Cancelling the use case is still cancellation. A `withTimeout` inside the step's own block keeps
 * failing as [Failure.Unexpected].
 */
public fun <I, O, E> Workflow<I, O, E>.timeout(duration: Duration, error: () -> E): Workflow<I, O, E> {
  require(duration.isPositive()) { "timeout must be positive, was $duration" }
  val original = this
  return Workflow(id) { input ->
    val output = withTimeoutOrNull(duration) { Some(runInside(original, input)) } ?: raise(error())
    output.value
  }
}

/**
 * Returns a step that runs this one again while [schedule] allows it. After each failed attempt the
 * schedule receives the [Failure] and decides whether to try again and how long to wait. When it
 * stops, the step fails with the last failure. The step keeps its id and is recorded once.
 *
 * Only the events of the successful attempt are kept, so a retried step never reports work from an
 * attempt that failed. Cancelling the use case is never retried.
 *
 * Retry thrown exceptions and transient errors, not domain decisions:
 *
 * ```kotlin
 * val transient = Schedule.exponential<Failure<PaymentError>>(200.milliseconds)
 *   .and(Schedule.recurs(2))
 *   .doWhile { failure, _ -> failure is Failure.Unexpected || failure == Failure.Raised(PaymentError.TimedOut) }
 *
 * val chargeCard = workflow<PricedOrder, PaidOrder, PaymentError>("charge-card") { payments.capture(it) }
 *   .timeout(2.seconds) { PaymentError.TimedOut }
 *   .retry(transient)
 * ```
 *
 * Order matters: `.timeout(d).retry(s)` limits each attempt, and `.retry(s).timeout(d)` limits all
 * attempts together.
 */
public fun <I, O, E> Workflow<I, O, E>.retry(schedule: Schedule<Failure<E>, *>): Workflow<I, O, E> {
  val original = this
  return Workflow(id) { input ->
    val result = failures.retry(schedule) { attempt(original, input).bind() }
    absorb(result)
    result.state
  }
}

/**
 * Returns a step that handles the errors this one raises with [handler], which returns an output in
 * their place or raises an error of type [F]. The step keeps its id.
 *
 * ```kotlin
 * .then(chargeCard.recover { order, error ->
 *   if (error is PaymentError.Declined) queueForManualReview(order) else raise(OrderError.Payment(error))
 * })
 * ```
 *
 * Events from the failed run are dropped. Thrown exceptions are not handled: they stay
 * [Failure.Unexpected], so a bug is never turned into a fallback.
 */
public fun <I, O, E, F> Workflow<I, O, E>.recover(
  handler: suspend WorkflowStepScope<F>.(input: I, error: E) -> O,
): Workflow<I, O, F> {
  val original = this
  return Workflow(id) { input ->
    when (val outcome = attempt(original, input)) {
      is Either.Right -> outcome.value.also { absorb(it) }.state
      is Either.Left -> when (val failure = outcome.value) {
        is Failure.Raised -> handler(input, failure.error)
        is Failure.Unexpected -> failures.raise(failure)
      }
    }
  }
}

/**
 * Runs [workflow]'s block with its own event buffer and returns the outcome, so a caller can drop a
 * failed run's events. Exceptions, including a timeout the block started itself, become
 * [Failure.Unexpected]; cancellation of this coroutine propagates.
 */
internal suspend fun <I, O, E> WorkflowStepScope<*>.attempt(
  workflow: Workflow<I, O, E>,
  input: I,
): Either<Failure<E>, WorkflowResult<O>> {
  val outcome: Either<Failure<Any?>, WorkflowResult<O>> = try {
    either {
      val scope = WorkflowStepScope<E>(this, context, recorder)
      scope.toResult(catchingExceptions { with(workflow) { scope.execute(input) } })
    }
  } catch (ex: CancellationException) {
    if (!currentCoroutineContext().isActive) throw ex
    Either.Left(Failure.Unexpected(ex))
  }
  // Only this workflow's errors, and errors of steps nested in it, are raised as Failure.Raised.
  @Suppress("UNCHECKED_CAST")
  return outcome as Either<Failure<E>, WorkflowResult<O>>
}

/** Runs [workflow]'s block in this scope, so it shares this step's events and execution record. */
internal suspend fun <I, O, E> WorkflowStepScope<E>.runInside(workflow: Workflow<I, O, E>, input: I): O =
  with(workflow) { this@runInside.execute(input) }

/**
 * Runs this workflow on its own and returns its new state, emitted events, and the context it ran with.
 *
 * Use cases run their steps for you; call this directly to unit-test a step.
 */
public suspend operator fun <I, O, E> Workflow<I, O, E>.invoke(
  input: I,
  context: WorkflowContext = WorkflowContext(),
): Either<Failure<E>, WorkflowResult<O>> = runStep(input, context, ExecutionRecorder())

/**
 * Receiver of [workflow] and `Pipeline.then(id) { }` blocks: an Arrow [Raise] for the step's error
 * type [E].
 *
 * Besides raising errors, a step can read the use case's [context] and [emit] domain events. [emit]
 * is safe to call from coroutines launched inside the step. Data for later steps belongs in the
 * step's returned state, not in the context.
 */
@WorkflowDsl
public class WorkflowStepScope<in E> private constructor(
  internal val failures: Raise<Failure<Any?>>,
  private val raiseError: (E) -> Nothing,
  /** Request metadata the use case was invoked with, such as a correlation ID. */
  public val context: WorkflowContext,
  internal val recorder: ExecutionRecorder,
  private val events: EventBuffer,
) : Raise<E> {
  internal constructor(failures: Raise<Failure<Any?>>, context: WorkflowContext, recorder: ExecutionRecorder) :
    this(failures, { failures.raise(Failure.Raised(it)) }, context, recorder, EventBuffer())

  override fun raise(r: E): Nothing = raiseError(r)

  /** Records a domain event emitted by this step. */
  public fun emit(event: Event) {
    events.add(listOf(event))
  }

  /** Merges the events of a nested step run into this one. */
  internal fun absorb(result: WorkflowResult<*>) {
    events.add(result.events)
  }

  /** A scope for the same step whose raised errors go through [raiseError] instead. */
  internal fun <F> withRaisedErrors(raiseError: (F) -> Nothing): WorkflowStepScope<F> =
    WorkflowStepScope(failures, raiseError, context, recorder, events)

  internal fun <O> toResult(state: O): WorkflowResult<O> = WorkflowResult(state, events.snapshot(), context)
}

/** Events collected by one step run, shared by the scopes of that run. */
internal class EventBuffer {
  private val events = mutableListOf<Event>()

  fun add(more: List<Event>) {
    synchronized(this) { events += more }
  }

  fun snapshot(): List<Event> = synchronized(this) { events.toList() }
}

/** Adapts a raise for [Failure]s into a raise for the application errors they wrap. */
internal class RaisedErrors<in E>(private val failures: Raise<Failure<Any?>>) : Raise<E> {
  override fun raise(r: E): Nothing = failures.raise(Failure.Raised(r))
}

/**
 * Collects the [WorkflowExecution]s of one use case run, including nested and launched steps, and
 * remembers which step raised each error.
 *
 * Parallel branches get child recorders so the step behind the error a join selects can be
 * attributed even when several branches fail, or fail with the same error instance.
 */
internal class ExecutionRecorder private constructor(
  private val parent: ExecutionRecorder?,
  private val launched: Boolean,
) {
  constructor() : this(null, false)

  private val root: ExecutionRecorder = parent?.root ?: this
  private val executions = mutableListOf<WorkflowExecution>()
  private val failedStepIds = java.util.IdentityHashMap<Failure<*>, String>()

  /** A recorder for launched side effects: their executions are kept, but never count as the failing step. */
  fun forLaunched(): ExecutionRecorder = ExecutionRecorder(this, launched = true)

  /** A recorder for one parallel branch. */
  fun forBranch(): ExecutionRecorder = ExecutionRecorder(this, launched)

  fun record(execution: WorkflowExecution, error: Failure<*>?) {
    synchronized(root) {
      root.executions += execution
      // The innermost step records an error first; enclosing steps that pass it on keep that id.
      if (!launched && error != null) forEachLevel { it.failedStepIds.putIfAbsent(error, execution.workflowId) }
    }
  }

  /** Marks [stepId] as the step behind [error], replacing what completion order recorded. */
  fun attribute(error: Failure<*>, stepId: String?) {
    if (launched || stepId == null) return
    synchronized(root) { forEachLevel { it.failedStepIds[error] = stepId } }
  }

  fun snapshot(): List<WorkflowExecution> = synchronized(root) { root.executions.toList() }

  /** The id of the main-pipeline step that raised [error], or `null` if no step raised it. */
  fun failedStepId(error: Failure<*>): String? = synchronized(root) { failedStepIds[error] }

  private inline fun forEachLevel(action: (ExecutionRecorder) -> Unit) {
    var level: ExecutionRecorder? = this
    while (level != null) {
      action(level)
      level = level.parent
    }
  }
}

/**
 * Runs this step, records its execution, and returns its result. When the step fails,
 * [onFailure] receives the events it collected before failing.
 */
internal suspend fun <I, O, E> Workflow<I, O, E>.runStep(
  input: I,
  context: WorkflowContext,
  recorder: ExecutionRecorder,
  onFailure: ((WorkflowResult<Nothing?>) -> Unit)? = null,
): Either<Failure<E>, WorkflowResult<O>> {
  val startTime = Instant.now()
  val mark = TimeSource.Monotonic.markNow()
  var scope: WorkflowStepScope<E>? = null
  val result: Either<Failure<Any?>, WorkflowResult<O>> = try {
    either<Failure<Any?>, WorkflowResult<O>> {
      val stepScope = WorkflowStepScope<E>(this, context, recorder).also { scope = it }
      val state = catchingExceptions { stepScope.execute(input) }
      stepScope.toResult(state)
    }
  } catch (ex: CancellationException) {
    // A timeout the step started itself (its own withTimeout) is a failure of the step. Cancellation
    // of this coroutine, including a caller's timeout, keeps propagating.
    if (!currentCoroutineContext().isActive) throw ex
    Either.Left(Failure.Unexpected(ex))
  }
  val duration = mark.elapsedNow()
  recorder.record(
    WorkflowExecution(
      workflowName = id,
      workflowId = id,
      startTime = startTime,
      endTime = startTime.plusNanos(duration.inWholeNanoseconds),
      duration = duration,
      succeeded = result.isRight(),
    ),
    result.leftOrNull(),
  )
  if (result.isLeft() && onFailure != null) scope?.let { onFailure(it.toResult(null)) }
  // Only this step's errors, and errors of steps nested in it, are raised as Failure.Raised.
  @Suppress("UNCHECKED_CAST")
  return result as Either<Failure<E>, WorkflowResult<O>>
}

/**
 * Runs [block], turning non-fatal exceptions into [Failure.Unexpected]. Raised errors and
 * cancellation pass through.
 */
internal inline fun <A> Raise<Failure<Any?>>.catchingExceptions(block: () -> A): A =
  arrow.core.raise.catch(block) { ex -> raise(Failure.Unexpected(ex)) }
