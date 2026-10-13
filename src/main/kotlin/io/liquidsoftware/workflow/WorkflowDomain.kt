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
 * A failure raised by a workflow step, `startWith`, or a result projection.
 *
 * The library's own errors are nested here. Applications can also implement this interface with
 * their own sealed error types and raise them directly from steps; they reach the `errors` mapper
 * of `useCase` unchanged.
 *
 * ```kotlin
 * sealed interface OrderError : WorkflowError {
 *   data object OutOfStock : OrderError
 *   data class PaymentDeclined(val reason: String) : OrderError
 * }
 * ```
 */
public interface WorkflowError {
  /**
   * Stable, non-sensitive identifier used in logs and summaries. Defaults to the class name.
   */
  public val code: String
    get() = this::class.simpleName ?: "WorkflowError"

  /**
   * Human-readable description. It may contain user data, so summaries redact it unless asked not to.
   */
  public val message: String
    get() = code

  /** Input that failed validation. */
  public data class ValidationError(override val message: String) : WorkflowError

  /** A step could not complete. */
  public data class ExecutionError(override val message: String) : WorkflowError

  /** A business rule failed. Prefer your own [WorkflowError] types when you need structure. */
  public data class DomainError(
    override val code: String,
    override val message: String,
    val metadata: Map<String, String> = emptyMap(),
  ) : WorkflowError

  /** A step, `startWith`, or projection threw. */
  public data class ExceptionError(override val message: String, val ex: Throwable) : WorkflowError

  /** The use case was assembled in a way that cannot run, such as a missing expected event. */
  public data class CompositionError(override val message: String) : WorkflowError
}

/**
 * Kept so 0.8.x error mappers still compile. Step errors are no longer wrapped, so this returns
 * the error unchanged.
 */
@Deprecated("Step errors are no longer wrapped. Use the error directly.", ReplaceWith("this"))
public fun WorkflowError.rootCause(): WorkflowError = this

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
