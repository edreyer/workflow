package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.raise.either
import io.liquidsoftware.workflow.WorkflowError.ExceptionError
import java.time.Instant
import kotlin.reflect.KClass

// Base Workflow interface
abstract class Workflow<in I : WorkflowState, out O : WorkflowState> {

  abstract val id: String

  /**
   * Optional input type hint used by the DSL to avoid reflective erasure issues
   * (wrappers like parallelJoin supply this explicitly).
   */
  open val inputClass: KClass<out WorkflowState>?
    get() = WorkflowUtils.getWorkflowInputClass(this::class)

  protected open suspend fun executeWorkflow(input: I, context: WorkflowContext): Either<WorkflowError, WorkflowResult<O>> =
    executeWorkflow(input)

  protected open suspend fun executeWorkflow(input: I): Either<WorkflowError, WorkflowResult<O>> =
    throw IllegalStateException("Workflow ${this::class.simpleName ?: "Unknown"} must implement executeWorkflow")

  suspend fun execute(input: I, context: WorkflowContext = WorkflowContext()): Either<WorkflowError, WorkflowResult<O>> {
    val startTime = Instant.now()
    val result = either {
      // Either.catch handles CancellationException properly
      Either.catch {
        executeWorkflow(input, context).bind()
      }.mapLeft { ex ->
        raise(ExceptionError("An exception occurred", ex))
      }.bind()
    }
    val endTime = Instant.now()

    val execution = WorkflowExecution(
      workflowName = this::class.simpleName ?: "UnknownWorkflow",
      workflowId = id,
      startTime = startTime,
      endTime = endTime,
      succeeded = result.isRight()
    )

    return result
      .mapLeft { error ->
        if (error is WorkflowError.ExecutionContextError) {
          error
        } else {
          WorkflowError.ExecutionContextError(error, execution)
        }
      }
      .map { it.copy(context = it.context.addExecution(execution)) }
  }
}

abstract class UseCase<C : UseCaseCommand> {
  /**
   * Legacy execution method that returns only the list of emitted events.
   * Existing direct subclasses can continue implementing this method.
   */
  abstract suspend fun execute(command: C): Either<WorkflowError, UseCaseEvents>

  /**
   * Executes the use case and returns a detailed [UseCaseResult] containing the final state,
   * all emitted events, and the full execution context.
   *
   * @param command The input command for the use case.
   * @param context An optional initial [WorkflowContext] (e.g., containing correlation IDs).
   *
   * The default implementation preserves backwards compatibility for direct [UseCase] subclasses
   * by adapting event-only results into an [EventOnlyState]. DSL-built use cases override this
   * with the actual final state.
   */
  open suspend fun executeDetailed(
    command: C,
    context: WorkflowContext = WorkflowContext()
  ): Either<WorkflowError, UseCaseResult<WorkflowState>> =
    execute(command).map { UseCaseResult(EventOnlyState, it.events, context) }

  /**
   * Executes the use case and applies a [projector] function to the result.
   * Use this for custom transformations where the output is a hybrid of state, events, and context.
   *
   * @param command The input command for the use case.
   * @param context An optional initial [WorkflowContext].
   * @param projector A lambda that transforms [UseCaseResult] into your desired output type [O].
   */
  suspend fun <O> executeProjected(
    command: C,
    context: WorkflowContext = WorkflowContext(),
    projector: (UseCaseResult<WorkflowState>) -> Either<WorkflowError, O>
  ): Either<WorkflowError, O> =
    executeDetailed(command, context).flatMap(projector)

  /**
   * Executes the use case and returns the last emitted event of type [E].
   * Useful for controllers that need to return specific data contained within a domain event.
   *
   * @param command The input command for the use case.
   * @param context An optional initial [WorkflowContext].
   * @return [Either.Right] with the last event of type [E], or [Either.Left] with [WorkflowError.CompositionError]
   * if an event of type [E] was not emitted.
   */
  suspend inline fun <reified E : Event> executeForEvent(
    command: C,
    context: WorkflowContext = WorkflowContext()
  ): Either<WorkflowError, E> {
    val useCaseName = this::class.simpleName ?: "UseCase"
    return executeProjected(command, context) { it.requireLastEvent(useCaseName) }
  }

  /**
   * Executes the use case and returns the final state cast to [S].
   * Useful when the caller needs the complete final domain state after all transformations.
   *
   * @param command The input command for the use case.
   * @param context An optional initial [WorkflowContext].
   * @return [Either.Right] with the final state cast to [S], or [Either.Left] with [WorkflowError.CompositionError]
   * if the final state is not of type [S].
   */
  suspend inline fun <reified S : WorkflowState> executeForState(
    command: C,
    context: WorkflowContext = WorkflowContext()
  ): Either<WorkflowError, S> {
    val useCaseName = this::class.simpleName ?: "UseCase"
    return executeProjected(command, context) { it.requireState(useCaseName) }
  }
}

data object EventOnlyState : WorkflowState
