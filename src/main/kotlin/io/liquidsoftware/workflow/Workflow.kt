package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import io.liquidsoftware.workflow.WorkflowError.ExceptionError
import java.time.Instant

// Base Workflow interface
abstract class Workflow<I : WorkflowInput, E : Event> {

  abstract val id: String

  protected abstract suspend fun executeWorkflow(input: I): Either<WorkflowError, WorkflowResult>

  suspend fun execute(input: I): Either<WorkflowError, WorkflowResult> {
    val startTime = Instant.now()
    val result = either {
      // Either.catch handles CancellationException properly
      Either.catch {
        executeWorkflow(input).bind()
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

    val updatedContext = result.fold(
      { WorkflowContext().addExecution(execution) },
      { wr -> wr.context.addExecution(execution) }
    )

    return result.map { it.copy(context = updatedContext) }
  }
}

abstract class UseCase<UCC : UseCaseCommand> {
  abstract suspend fun execute(command: UCC): Either<WorkflowError, WorkflowResult>
}
