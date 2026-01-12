package io.liquidsoftware.workflow

import java.time.Instant
import java.util.UUID
import kotlin.reflect.KProperty1

interface WorkflowInput
interface WorkflowCommand : WorkflowInput
interface WorkflowQuery : WorkflowInput

interface UseCaseCommand

interface Event {
  val id: UUID
  val timestamp: Instant
}

sealed class WorkflowError {
  data class ValidationError(val message: String) : WorkflowError()
  data class ExecutionError(val message: String) : WorkflowError()
  data class ExceptionError(val message: String, val ex: Throwable) : WorkflowError()
  data class CompositionError(val message: String, val ex: Throwable) : WorkflowError()
  data class ExecutionContextError(val error: WorkflowError, val execution: WorkflowExecution) : WorkflowError()
  data class ChainError(val error: WorkflowError) : WorkflowError()
}

data class WorkflowResult(
  val events: List<Event> = emptyList(),
  val context: WorkflowContext = WorkflowContext()
) {

  inline fun <reified T : Event, R> getFromEvent(property: KProperty1<T, R>): R? =
    events.filterIsInstance<T>().firstOrNull()
      ?.let { event -> property.get(event) }

  fun combine(other: WorkflowResult): WorkflowResult {
    val combinedContext = this.context.combine(other.context)
    val combinedEvents = this.events + other.events
    return WorkflowResult(combinedEvents, combinedContext)
  }
}

data class WorkflowExecution(
  val workflowName: String,
  val workflowId: String,
  val startTime: Instant,
  val endTime: Instant,
  val succeeded: Boolean
)

data class WorkflowContext(
  val data: Map<String, Any> = emptyMap(),
  val executions: List<WorkflowExecution> = emptyList()
) {
  fun addData(key: String, value: Any): WorkflowContext {
    val newData = data + (key to value)
    return copy(data = newData)
  }

  /**
   * Retrieves typed data from context with type safety.
   * @return The value if present and matches type T, otherwise the default value
   */
  inline fun <reified T> getTypedData(key: String, default: T? = null): T? {
    val value = data[key]
    return if (value is T) value else default
  }

  fun combine(other: WorkflowContext): WorkflowContext {
    val combinedData = data + other.data
    val combinedExecutions = executions + other.executions
    return copy(data = combinedData, executions = combinedExecutions)
  }

  fun addExecution(execution: WorkflowExecution): WorkflowContext {
    val newExecutions = executions + execution
    return copy(executions = newExecutions)
  }

}
