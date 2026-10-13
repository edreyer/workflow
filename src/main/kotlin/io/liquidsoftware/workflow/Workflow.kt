package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.time.Instant
import kotlin.time.TimeSource

/**
 * Marks the receivers of the workflow DSL so a nested block cannot silently call a member of an
 * enclosing one.
 */
@DslMarker
@Target(AnnotationTarget.CLASS)
public annotation class WorkflowDsl

/**
 * One focused step of a use case: it turns input state [I] into output state [O].
 *
 * [execute] runs inside a [WorkflowStepScope], which is an Arrow [Raise] for [WorkflowError]. Fail
 * with `ensure`, `ensureNotNull`, `raise`, or `bind()`; record events with
 * [WorkflowStepScope.emit]; add context data with [WorkflowStepScope.put]. Thrown exceptions become
 * [WorkflowError.ExceptionError].
 *
 * ```kotlin
 * class ReserveInventory(private val inventory: Inventory) : Workflow<ValidatedOrder, ReservedOrder> {
 *   override val id = "reserve-inventory"
 *
 *   override suspend fun WorkflowStepScope.execute(input: ValidatedOrder): ReservedOrder {
 *     val reservation = ensureNotNull(inventory.reserve(input.items)) { OrderError.OutOfStock }
 *     emit(InventoryReserved(input.orderId, reservation.id))
 *     return ReservedOrder(input, reservation)
 *   }
 * }
 * ```
 *
 * For a step that needs no class of its own, use the [workflow] function.
 */
public interface Workflow<in I, out O> {
  /** Stable identifier used in execution history, logs, and summaries. */
  public val id: String

  public suspend fun WorkflowStepScope.execute(input: I): O
}

/**
 * Defines a [Workflow] from a lambda.
 *
 * ```kotlin
 * val validateOrder = workflow<OrderDraft, ValidatedOrder>("validate-order") { draft ->
 *   ensure(draft.items.isNotEmpty()) { WorkflowError.ValidationError("Order has no items") }
 *   emit(OrderValidated(draft.orderId))
 *   ValidatedOrder(draft.orderId, draft.items)
 * }
 * ```
 *
 * @param id Stable identifier used in execution history, logs, and summaries.
 */
public fun <I, O> workflow(
  id: String,
  block: suspend WorkflowStepScope.(input: I) -> O,
): Workflow<I, O> = LambdaWorkflow(id, block)

/**
 * Defines a [Workflow] that checks its input and passes it on unchanged, such as an authorization
 * check. Fail with `ensure` or `raise`.
 *
 * ```kotlin
 * val authorize = guard<PlaceOrder>("authorize") { command ->
 *   ensure(command.customer.canOrder) { OrderError.NotAllowed }
 * }
 * ```
 *
 * @param id Stable identifier used in execution history, logs, and summaries.
 */
public fun <I> guard(
  id: String,
  block: suspend WorkflowStepScope.(input: I) -> Unit,
): Workflow<I, I> = LambdaWorkflow(id) { input -> block(input); input }

/**
 * Runs this workflow on its own and returns its new state, emitted events, and context.
 *
 * Use cases run their steps for you; call this directly to unit-test a step.
 */
public suspend operator fun <I, O> Workflow<I, O>.invoke(
  input: I,
  context: WorkflowContext = WorkflowContext(),
): Either<WorkflowError, WorkflowResult<O>> = runStep(input, context, ExecutionRecorder())

/**
 * Receiver of [Workflow.execute] and [workflow] blocks.
 *
 * Besides raising [WorkflowError]s, a step can read the [context], [emit] domain events, and [put]
 * typed data into the context seen by later steps. These methods are safe to call from coroutines
 * launched inside the step.
 */
@WorkflowDsl
public class WorkflowStepScope internal constructor(
  raise: Raise<WorkflowError>,
  context: WorkflowContext,
  internal val recorder: ExecutionRecorder,
) : Raise<WorkflowError> by raise {
  private val lock = Any()
  private val events = mutableListOf<Event>()
  private var currentContext = context

  /** The context as the previous steps left it, plus anything this step has [put]. */
  public val context: WorkflowContext
    get() = synchronized(lock) { currentContext }

  /** Records a domain event emitted by this step. */
  public fun emit(event: Event) {
    synchronized(lock) { events += event }
  }

  /** Adds typed data to the context seen by later steps. */
  public fun <T : Any> put(key: Key<T>, value: T) {
    synchronized(lock) { currentContext = currentContext.with(key, value) }
  }

  /** Merges the events and context of a nested step run into this one. */
  internal fun absorb(result: WorkflowResult<*>) {
    synchronized(lock) {
      events += result.events
      currentContext += result.context
    }
  }

  internal fun <O> toResult(state: O): WorkflowResult<O> =
    synchronized(lock) { WorkflowResult(state, events.toList(), currentContext) }
}

private class LambdaWorkflow<I, O>(
  override val id: String,
  private val block: suspend WorkflowStepScope.(I) -> O,
) : Workflow<I, O> {
  override suspend fun WorkflowStepScope.execute(input: I): O = block(input)
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
  private val failedStepIds = java.util.IdentityHashMap<WorkflowError, String>()

  /** A recorder for launched side effects: their executions are kept, but never count as the failing step. */
  fun forLaunched(): ExecutionRecorder = ExecutionRecorder(this, launched = true)

  /** A recorder for one parallel branch. */
  fun forBranch(): ExecutionRecorder = ExecutionRecorder(this, launched)

  fun record(execution: WorkflowExecution, error: WorkflowError?) {
    synchronized(root) {
      root.executions += execution
      // The innermost step records an error first; enclosing steps that pass it on keep that id.
      if (!launched && error != null) forEachLevel { it.failedStepIds.putIfAbsent(error, execution.workflowId) }
    }
  }

  /** Marks [stepId] as the step behind [error], replacing what completion order recorded. */
  fun attribute(error: WorkflowError, stepId: String?) {
    if (launched || stepId == null) return
    synchronized(root) { forEachLevel { it.failedStepIds[error] = stepId } }
  }

  fun snapshot(): List<WorkflowExecution> = synchronized(root) { root.executions.toList() }

  /** The id of the main-pipeline step that raised [error], or `null` if no step raised it. */
  fun failedStepId(error: WorkflowError): String? = synchronized(root) { failedStepIds[error] }

  private inline fun forEachLevel(action: (ExecutionRecorder) -> Unit) {
    var level: ExecutionRecorder? = this
    while (level != null) {
      action(level)
      level = level.parent
    }
  }
}

internal val Workflow<*, *>.executionName: String
  get() = if (this is LambdaWorkflow) id else this::class.simpleName ?: id

/**
 * Runs this step, records its execution, and returns its result. When the step fails,
 * [onFailure] receives the events and context it collected before failing.
 */
internal suspend fun <I, O> Workflow<I, O>.runStep(
  input: I,
  context: WorkflowContext,
  recorder: ExecutionRecorder,
  onFailure: ((WorkflowResult<Nothing?>) -> Unit)? = null,
): Either<WorkflowError, WorkflowResult<O>> {
  val startTime = Instant.now()
  val mark = TimeSource.Monotonic.markNow()
  var scope: WorkflowStepScope? = null
  val result: Either<WorkflowError, WorkflowResult<O>> = try {
    either<WorkflowError, WorkflowResult<O>> {
      val stepScope = WorkflowStepScope(this, context, recorder).also { scope = it }
      val state = catchingExceptions { stepScope.execute(input) }
      stepScope.toResult(state)
    }
  } catch (ex: CancellationException) {
    // A timeout the step started itself (its own withTimeout) is a failure of the step. Cancellation
    // of this coroutine, including a caller's timeout, keeps propagating.
    if (!currentCoroutineContext().isActive) throw ex
    Either.Left(WorkflowError.ExceptionError("An exception occurred", ex))
  }
  val duration = mark.elapsedNow()
  recorder.record(
    WorkflowExecution(
      workflowName = executionName,
      workflowId = id,
      startTime = startTime,
      endTime = startTime.plusNanos(duration.inWholeNanoseconds),
      duration = duration,
      succeeded = result.isRight(),
    ),
    result.leftOrNull(),
  )
  if (result.isLeft() && onFailure != null) scope?.let { onFailure(it.toResult(null)) }
  return result
}

/**
 * Runs [block], turning non-fatal exceptions into [WorkflowError.ExceptionError]. Raised errors and
 * cancellation pass through.
 */
internal inline fun <A> Raise<WorkflowError>.catchingExceptions(block: () -> A): A =
  arrow.core.raise.catch(block) { ex -> raise(WorkflowError.ExceptionError("An exception occurred", ex)) }
