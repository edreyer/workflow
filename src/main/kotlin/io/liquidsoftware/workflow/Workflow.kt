package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import io.liquidsoftware.workflow.WorkflowError.ExceptionError
import java.time.Instant

// Base Workflow interface
abstract class Workflow<I : Input, E: Event> {

    abstract val id: String

    protected abstract suspend fun executeWorkflow(input: I): Either<WorkflowError, WorkflowResult>

    suspend fun execute(input: I): Either<WorkflowError, WorkflowResult> {
        val startTime = Instant.now()
        val result = either {
            try {
                executeWorkflow(input).bind()
            } catch (ex: Exception) {
                raise(ExceptionError("An exception occurred", ex))
            }
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
            { WorkflowContext().addExecution(execution)},
            { wr -> wr.context.addExecution(execution) }
        )

        return result.map { it.copy(context = updatedContext) }
    }
}

abstract class UseCase<I : Input> {
    abstract suspend fun execute(input: I): Either<WorkflowError, WorkflowResult>
}
