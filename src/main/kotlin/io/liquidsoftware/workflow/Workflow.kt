package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.raise.either
import io.liquidsoftware.workflow.WorkflowError.ExceptionError
import java.time.Duration
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

  /**
   * Name recorded in [WorkflowExecution.workflowName]. Defaults to the class name.
   */
  internal open val executionName: String
    get() = this::class.simpleName ?: "UnknownWorkflow"

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
      workflowName = executionName,
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

/**
 * Lower-level workflow-native execution model.
 *
 * Most application code should use callable [UseCase] values built with the
 * top-level `useCase` DSL. This type exists to run composed workflow chains and
 * preserve detailed state, event, and context data internally.
 *
 * Workflow use cases emit debug-level invocation logs through SLF4J. Logs include only
 * operational metadata such as the configured use-case name, success/failure
 * outcome, duration, event count, and workflow count. Command input, state,
 * event payloads, projected output, and error messages are intentionally not logged.
 *
 * @param configuredName Optional safe name used in invocation logs and projection errors.
 */
abstract class WorkflowUseCase<C : UseCaseCommand>(
  private val configuredName: String? = null
) {
  /**
   * Safe operational name for this use case.
   */
  val name: String
    get() = configuredName ?: this::class.simpleName ?: "UseCase"

  /**
   * Implements the use case. Subclasses provide behavior here so public execution methods
   * can consistently apply cross-cutting behavior such as safe invocation logging.
   */
  protected abstract suspend fun executeUseCase(
    command: C,
    context: WorkflowContext
  ): Either<WorkflowError, UseCaseResult<WorkflowState>>

  internal suspend fun executeWithoutLogging(
    command: C,
    context: WorkflowContext
  ): Either<WorkflowError, UseCaseResult<WorkflowState>> =
    executeUseCase(command, context)

  /**
   * Executes the use case and returns a detailed [UseCaseResult] containing the final state,
   * all emitted events, and the full execution context.
   *
   * @param command The input command for the use case.
   * @param context An optional initial [WorkflowContext] (e.g., containing correlation IDs).
   *
   * Emits debug logs before and after execution without logging command, state, or event payload data.
   */
  suspend fun executeDetailed(
    command: C,
    context: WorkflowContext = WorkflowContext()
  ): Either<WorkflowError, UseCaseResult<WorkflowState>> {
    val startTime = Instant.now()
    logUseCaseStarted(name)
    val result = executeUseCase(command, context)
    val duration = Duration.between(startTime, Instant.now())
    logUseCaseFinished(name, result.isRight(), duration, result.logMetadata())
    return result
  }

  /**
   * Executes the use case and returns only the emitted events.
   */
  suspend fun execute(command: C): Either<WorkflowError, UseCaseEvents> {
    val startTime = Instant.now()
    logUseCaseStarted(name)
    val detailed = executeUseCase(command, WorkflowContext())
    val result = detailed.map { it.toUseCaseEvents() }
    val duration = Duration.between(startTime, Instant.now())
    logUseCaseFinished(name, result.isRight(), duration, detailed.logMetadata())
    return result
  }

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
  ): Either<WorkflowError, O> {
    val startTime = Instant.now()
    logUseCaseStarted(name)
    val detailed = executeUseCase(command, context)
    val result =
      Either.catch { detailed.flatMap(projector) }
        .mapLeft { WorkflowError.ExceptionError("An exception occurred", it) }
        .flatMap { it }
    val duration = Duration.between(startTime, Instant.now())
    logUseCaseFinished(name, result.isRight(), duration, detailed.logMetadata())
    return result
  }

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
    return executeProjected(command, context) { it.requireLastEvent(name) }
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
    return executeProjected(command, context) { it.requireState(name) }
  }
}

data object EventOnlyState : WorkflowState
