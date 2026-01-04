package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import io.liquidsoftware.workflow.WorkflowError.ExceptionError
import java.time.Instant
import java.util.UUID
import kotlin.reflect.KClass

/**
 * Type-safe property key that contains both name and type information
 */
data class Key<T : Any>(
    val id: String,
    val type: KClass<T>
) {
    companion object {
        /**
         * Creates a typed key with reified type parameter
         */
        inline fun <reified T : Any> of(id: String): Key<T> = Key(id, T::class)

        /**
         * Creates a typed key for common types with convenience methods
         */
        fun string(id: String): Key<String> = Key(id, String::class)
        fun uuid(id: String): Key<UUID> = Key(id, UUID::class)
        fun double(id: String): Key<Double> = Key(id, Double::class)
        fun int(id: String): Key<Int> = Key(id, Int::class)
        fun boolean(id: String): Key<Boolean> = Key(id, Boolean::class)
        fun long(id: String): Key<Long> = Key(id, Long::class)
    }
}

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

    return result
      .mapLeft { error -> WorkflowError.ExecutionContextError(error, execution) }
      .map { it.copy(context = updatedContext) }
  }
}

abstract class UseCase<UCC : UseCaseCommand> {
  abstract suspend fun execute(command: UCC): Either<WorkflowError, WorkflowResult>
}
