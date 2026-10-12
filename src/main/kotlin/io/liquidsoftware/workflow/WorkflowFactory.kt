package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import kotlin.reflect.KClass

/**
 * Defines a workflow step from a lambda instead of a [Workflow] subclass.
 *
 * The block receives the typed input state and returns the next state. It runs inside a
 * [WorkflowStepScope], which is an Arrow [Raise] for [WorkflowError], so failures are written
 * with `ensure`, `ensureNotNull`, `raise`, or `bind()` rather than by building `Either` values.
 * Events and context data are added with [WorkflowStepScope.emit] and [WorkflowStepScope.put].
 *
 * ```kotlin
 * val validateOrder = workflow<OrderDraft, ValidatedOrder>("validate-order") { draft ->
 *   ensure(draft.items.isNotEmpty()) { WorkflowError.ValidationError("Order has no items") }
 *   emit(OrderValidated(draft.orderId))
 *   ValidatedOrder(draft.orderId, draft.items)
 * }
 * ```
 *
 * The input type is captured at compile time, so the step does not need reflection to find it.
 * Thrown exceptions are converted to [WorkflowError.ExceptionError] like any other workflow.
 *
 * @param id Stable workflow identifier used in execution history, errors, and summaries.
 */
inline fun <reified I : WorkflowState, O : WorkflowState> workflow(
  id: String,
  noinline block: suspend WorkflowStepScope.(input: I) -> O,
): Workflow<I, O> = LambdaWorkflow(id, I::class, block)

/**
 * Receiver for steps defined with [workflow].
 *
 * Besides raising [WorkflowError]s, a step can read the incoming [context], [emit] domain events,
 * and [put] typed data into the context passed to later steps. These methods are safe to call
 * from coroutines launched inside the step.
 */
@WorkflowDsl
class WorkflowStepScope @PublishedApi internal constructor(
  raise: Raise<WorkflowError>,
  /** The context as it was when this step started. */
  val context: WorkflowContext,
) : Raise<WorkflowError> by raise {
  private val lock = Any()
  private val events = mutableListOf<Event>()
  private val data = linkedMapOf<String, Any>()

  /** Records a domain event emitted by this step. */
  fun emit(event: Event) {
    synchronized(lock) { events += event }
  }

  /** Adds typed data to the context seen by later steps. */
  fun <T : Any> put(key: Key<T>, value: T) {
    synchronized(lock) { data[key.id] = value }
  }

  internal fun <O : WorkflowState> toResult(state: O): WorkflowResult<O> =
    synchronized(lock) {
      WorkflowResult(state, events.toList(), WorkflowContext(data = data.toMap()))
    }
}

@PublishedApi
internal class LambdaWorkflow<I : WorkflowState, O : WorkflowState>(
  override val id: String,
  override val inputClass: KClass<I>,
  private val block: suspend WorkflowStepScope.(I) -> O,
) : Workflow<I, O>() {
  override val executionName: String
    get() = id

  override suspend fun executeWorkflow(input: I, context: WorkflowContext): Either<WorkflowError, WorkflowResult<O>> =
    either {
      val scope = WorkflowStepScope(this, context)
      scope.toResult(scope.block(input))
    }
}
