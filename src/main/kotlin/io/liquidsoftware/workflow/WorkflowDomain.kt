package io.liquidsoftware.workflow

import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

/**
 * Marks a domain fact emitted while a use case runs. It requires no members, so events carry only
 * the fields your domain needs.
 */
public interface Event

/**
 * Optional base type for events that want a generated [id] and [timestamp]. Pass your own values
 * to use an injected clock or ID generator.
 */
public abstract class BaseEvent(
  public val id: UUID = UUID.randomUUID(),
  public val timestamp: Instant = Instant.now(),
) : Event

/**
 * Returns the last event of type [T], or `null` when none was emitted.
 */
public inline fun <reified T : Event> List<Event>.lastEvent(): T? =
  lastOrNull { it is T } as T?

/**
 * Identifies a typed value stored in a [WorkflowContext].
 *
 * Keys compare by identity, so declare each key once and share it:
 *
 * ```kotlin
 * object Keys {
 *   val TENANT = Key<TenantId>("tenant")
 *   val FLAGS = Key<Set<String>>("flags")
 * }
 * ```
 *
 * Because the value type lives in the type parameter rather than in a `KClass`, generic types such
 * as `List<String>` keep their full type.
 *
 * @property name Diagnostic name, used in `toString` only.
 */
public class Key<T : Any>(public val name: String) {
  /**
   * Pairs this key with a value of its type, for [WorkflowContext.of]. As a member it takes
   * precedence over the standard `to`, so a value of the wrong type does not compile.
   */
  public infix fun to(value: T): Entry<T> = Entry(this, value)

  override fun toString(): String = "Key($name)"

  /** A key and a value of its type. */
  public class Entry<T : Any> internal constructor(public val key: Key<T>, public val value: T)
}

/**
 * Immutable, typed metadata that flows through a use case, such as a correlation ID or feature
 * flags. Pass it when invoking the use case; steps read it through [WorkflowStepScope.context]. Data
 * that steps produce belongs in the typed state, not here.
 */
public class WorkflowContext private constructor(
  private val data: Map<Key<*>, Any>,
) {
  public constructor() : this(emptyMap())

  /** Returns the value stored for [key], or `null`. */
  @Suppress("UNCHECKED_CAST")
  public operator fun <T : Any> get(key: Key<T>): T? = data[key] as T?

  /** Returns `true` when a value is stored for [key]. */
  public operator fun contains(key: Key<*>): Boolean = key in data

  /** Returns a copy with [key] set to [value]. */
  public fun <T : Any> with(key: Key<T>, value: T): WorkflowContext = WorkflowContext(data + Pair(key, value))

  /** Returns a copy with every entry of [other] added; [other] wins on conflicts. */
  public operator fun plus(other: WorkflowContext): WorkflowContext =
    if (other.data.isEmpty()) this else WorkflowContext(data + other.data)

  override fun equals(other: Any?): Boolean = other is WorkflowContext && other.data == data
  override fun hashCode(): Int = data.hashCode()
  override fun toString(): String = "WorkflowContext(keys=${data.keys})"

  public companion object {
    /** Correlation ID for logs and summaries. */
    public val CORRELATION_ID: Key<String> = Key("correlationId")

    /** Messages for launched side effects that failed or timed out. */
    public val LAUNCHED_FAILURES: Key<List<String>> = Key("launchedFailures")

    /** Builds a context from typed entries, written `key to value`. */
    public fun of(vararg entries: Key.Entry<*>): WorkflowContext =
      WorkflowContext(entries.associate { Pair(it.key, it.value) })
  }
}

/**
 * The outcome of running a workflow step on its own: the new state, the events the step emitted,
 * and the context it ran with.
 */
public data class WorkflowResult<out S>(
  val state: S,
  val events: List<Event> = emptyList(),
  val context: WorkflowContext = WorkflowContext(),
)

/**
 * One workflow step run, recorded for logging and [UseCaseSummary].
 *
 * @property duration Measured with a monotonic clock; [startTime] and [endTime] are wall-clock
 * timestamps for display only.
 */
public data class WorkflowExecution(
  val workflowName: String,
  val workflowId: String,
  val startTime: Instant,
  val endTime: Instant,
  val duration: Duration,
  val succeeded: Boolean,
)

/**
 * Why a use case or step failed: an application error that was raised, or an exception nobody
 * expected. The `errors` mapper of `useCase` receives one, so its `when` can be exhaustive:
 *
 * ```kotlin
 * fun toApiError(failure: Failure<OrderError>): ApiError = when (failure) {
 *   is Failure.Raised -> when (val error = failure.error) {
 *     OrderError.EmptyOrder -> ApiError.BadRequest("Order has no items")
 *     is OrderError.PaymentDeclined -> ApiError.PaymentRequired(error.reason)
 *   }
 *   is Failure.Unexpected -> ApiError.Internal
 * }
 * ```
 */
public sealed interface Failure<out E> {
  /** A step, `startWith`, or `result` raised [error]. */
  public data class Raised<out E>(val error: E) : Failure<E>

  /**
   * A step, `startWith`, or `result` threw [cause], or the use case was assembled in a way that
   * cannot run, such as a missing expected event.
   */
  public data class Unexpected(val cause: Throwable) : Failure<Nothing>
}

/**
 * Former marker for workflow state. Any type can now be workflow state.
 */
@Deprecated("Any type can be workflow state. Remove this supertype.")
public interface WorkflowState

/**
 * Former marker for use case input. Any type can now be use case input.
 */
@Deprecated("Any type can be use case input. Remove this supertype.")
public interface UseCaseCommand
