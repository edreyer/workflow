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
abstract class Workflow<in I : WorkflowState, out O : WorkflowState> {

  abstract val id: String

  /**
   * Optional input type hint used by the DSL to avoid reflective erasure issues
   * (wrappers like parallelJoin supply this explicitly).
   */
  open val inputClass: KClass<out WorkflowState>?
    get() = WorkflowUtils.getWorkflowInputClass(this::class)

  protected abstract suspend fun executeWorkflow(input: I): Either<WorkflowError, WorkflowResult<O>>

  suspend fun execute(input: I): Either<WorkflowError, WorkflowResult<O>> {
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
  abstract suspend fun execute(command: C): Either<WorkflowError, UseCaseEvents>
}
