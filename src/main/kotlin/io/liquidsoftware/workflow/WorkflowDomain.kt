package io.liquidsoftware.workflow

import arrow.core.Either
import java.time.Instant
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

interface WorkflowState

interface UseCaseCommand

interface Event {
  val id: UUID
  val timestamp: Instant
}

/**
 * Type-safe property key that contains both name and type information.
 * Used for storing and retrieving data in [WorkflowContext].
 *
 * @param T The type of the value associated with this key.
 * @property id The string identifier for the key.
 * @property type The KClass representing the type of the value.
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

/**
 * Common extension functions for a list of events.
 */
inline fun <reified T : Event, R> List<Event>.getFromEvent(property: KProperty1<T, R>): R? =
  filterIsInstance<T>().firstOrNull()?.let { property.get(it) }

inline fun <reified T : Event> List<Event>.lastEvent(): T? =
  filterIsInstance<T>().lastOrNull()

abstract class BaseEvent(
  final override val id: UUID = UUID.randomUUID(),
  final override val timestamp: Instant = Instant.now(),
) : Event

data class UseCaseEvents(val events: List<Event>)

data class UseCaseResult<out S : WorkflowState>(
  val state: S,
  val events: List<Event>,
  val context: WorkflowContext = WorkflowContext()
) {
  inline fun <reified T : Event, R> getFromEvent(property: KProperty1<T, R>): R? =
    events.getFromEvent(property)

  inline fun <reified T : Event> lastEvent(): T? =
    events.lastEvent()

  inline fun <reified T : Event> requireLastEvent(useCaseName: String = "UseCase"): Either<WorkflowError, T> =
    lastEvent<T>()?.let { Either.Right(it) }
      ?: Either.Left(
        WorkflowError.CompositionError(
          "Use case $useCaseName did not emit expected event ${T::class.simpleName}",
          IllegalStateException("Missing expected event ${T::class.simpleName}")
        )
      )

  inline fun <reified T : WorkflowState> requireState(useCaseName: String = "UseCase"): Either<WorkflowError, T> =
    when (val currentState = state) {
      is T -> Either.Right(currentState)
      else -> Either.Left(
        WorkflowError.CompositionError(
          "Use case $useCaseName ended with state ${currentState::class.simpleName} instead of expected state ${T::class.simpleName}",
          IllegalStateException("Unexpected final state ${currentState::class.simpleName}")
        )
      )
    }

  fun toUseCaseEvents(): UseCaseEvents = UseCaseEvents(events)
}

sealed class WorkflowError {
  data class ValidationError(val message: String) : WorkflowError()
  data class ExecutionError(val message: String) : WorkflowError()
  data class DomainError(
    val code: String,
    val message: String,
    val metadata: Map<String, String> = emptyMap()
  ) : WorkflowError()
  data class ExceptionError(val message: String, val ex: Throwable) : WorkflowError()
  data class CompositionError(val message: String, val ex: Throwable) : WorkflowError()
  data class ExecutionContextError(val error: WorkflowError, val execution: WorkflowExecution) : WorkflowError()
  data class ChainError(val error: WorkflowError) : WorkflowError()
}

data class WorkflowResult<out S : WorkflowState>(
  val state: S,
  val events: List<Event> = emptyList(),
  val context: WorkflowContext = WorkflowContext()
) {

  inline fun <reified T : Event, R> getFromEvent(property: KProperty1<T, R>): R? =
    events.getFromEvent(property)

  fun mergePrevious(previous: WorkflowResult<WorkflowState>): WorkflowResult<S> {
    val combinedContext = previous.context.combine(context)
    val combinedEvents = previous.events + events
    return WorkflowResult(state, combinedEvents, combinedContext)
  }

  fun toUseCaseResult(): UseCaseResult<S> = UseCaseResult(
    state = state,
    events = events,
    context = context
  )
}

data class WorkflowExecution(
  val workflowName: String,
  val workflowId: String,
  val startTime: Instant,
  val endTime: Instant,
  val succeeded: Boolean
)

/**
 * A container for metadata and execution history that flows through a workflow chain.
 *
 * @property data A map of additional data, ideally accessed via type-safe [Key]s.
 * @property executions A list of all workflows executed within the current use case.
 */
data class WorkflowContext(
  val data: Map<String, Any> = emptyMap(),
  val executions: List<WorkflowExecution> = emptyList()
) {
  /**
   * Adds data to the context using a typed key.
   */
  fun <T : Any> addData(key: Key<T>, value: T): WorkflowContext {
    val newData = data + (key.id to value)
    return copy(data = newData)
  }

  /**
   * Legacy method to add data using a string key.
   */
  fun addData(key: String, value: Any): WorkflowContext {
    val newData = data + (key to value)
    return copy(data = newData)
  }

  /**
   * Retrieves typed data from context using a typed key.
   * @return The value if present and matches the key's type, otherwise null.
   */
  @Suppress("UNCHECKED_CAST")
  fun <T : Any> get(key: Key<T>): T? {
    val value = data[key.id]
    return if (key.type.isInstance(value)) value as T else null
  }

  /**
   * Legacy method to retrieve typed data from context using a string key and reified type.
   * @return The value if present and matches type T, otherwise the default value.
   */
  inline fun <reified T> getTypedData(key: String, default: T? = null): T? {
    val value = data[key]
    return if (value is T) value else default
  }

  fun combine(other: WorkflowContext): WorkflowContext {
    val combinedData = data + other.data
    val combinedExecutions = mergeExecutions(executions, other.executions)
    return copy(data = combinedData, executions = combinedExecutions)
  }

  fun addExecution(execution: WorkflowExecution): WorkflowContext {
    val newExecutions = executions + execution
    return copy(executions = newExecutions)
  }

  companion object {
    val CORRELATION_ID = Key.string("correlationId")
    val LAUNCHED_FAILURES = Key.of<List<String>>("launchedFailures")
  }
}

private fun mergeExecutions(
  current: List<WorkflowExecution>,
  incoming: List<WorkflowExecution>
): List<WorkflowExecution> {
  if (current.isEmpty()) {
    return incoming
  }
  if (incoming.isEmpty()) {
    return current
  }

  val commonPrefixLength = current.zip(incoming)
    .takeWhile { (left, right) -> left == right }
    .count()

  return if (commonPrefixLength == 0) {
    current + incoming
  } else {
    current.take(commonPrefixLength) +
      current.drop(commonPrefixLength) +
      incoming.drop(commonPrefixLength)
  }
}
