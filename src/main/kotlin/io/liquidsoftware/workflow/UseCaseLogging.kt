package io.liquidsoftware.workflow

import org.slf4j.LoggerFactory
import kotlin.time.Duration

private val useCaseLogger = LoggerFactory.getLogger("io.liquidsoftware.workflow.UseCase")

public enum class OutcomeStyle {
  Plain,
  AnsiColor,
}

public enum class DefaultSummaryFields {
  Disabled,
  Enabled,
}

/**
 * Logging and summary settings for use cases. Build one with [useCaseConfig] at application
 * startup and pass it to each `useCase`; use cases built without one use [Default].
 */
public class UseCaseConfig private constructor(
  internal val outcomeStyle: OutcomeStyle,
  internal val defaultSummaryFields: DefaultSummaryFields,
  internal val summaryListeners: List<(UseCaseSummary) -> Unit>,
  internal val launchTimeout: Duration?,
) {
  internal fun copy(
    outcomeStyle: OutcomeStyle = this.outcomeStyle,
    defaultSummaryFields: DefaultSummaryFields = this.defaultSummaryFields,
    summaryListeners: List<(UseCaseSummary) -> Unit> = this.summaryListeners,
    launchTimeout: Duration? = this.launchTimeout,
  ): UseCaseConfig = UseCaseConfig(outcomeStyle, defaultSummaryFields, summaryListeners, launchTimeout)

  public companion object {
    /** Plain outcomes, default summary fields enabled, no summary listeners, and no launch timeout. */
    public val Default: UseCaseConfig =
      UseCaseConfig(OutcomeStyle.Plain, DefaultSummaryFields.Enabled, emptyList(), launchTimeout = null)
  }
}

/**
 * Builds a [UseCaseConfig], starting from [base]. Pass an application-wide config as [base] to
 * adjust it for one use case.
 *
 * ```kotlin
 * val appConfig = useCaseConfig { onSummary { summary -> metrics.record(summary) } }
 * val placeOrder = useCase("PlaceOrder", config = appConfig) { ... }
 * ```
 */
public fun useCaseConfig(
  base: UseCaseConfig = UseCaseConfig.Default,
  configure: UseCaseConfigBuilder.() -> Unit,
): UseCaseConfig = UseCaseConfigBuilder(base).apply(configure).build()

/**
 * Receiver of [useCaseConfig].
 */
@WorkflowDsl
public class UseCaseConfigBuilder internal constructor(private var config: UseCaseConfig) {
  /** Plain `outcome=success`, or ANSI-colored outcomes for local console logs. */
  public fun outcomeStyle(value: OutcomeStyle) {
    config = config.copy(outcomeStyle = value)
  }

  /** Whether finish logs include `durationMs`, `events`, and `workflows`. */
  public fun defaultSummaryFields(value: DefaultSummaryFields) {
    config = config.copy(defaultSummaryFields = value)
  }

  /**
   * Calls [listener] with a [UseCaseSummary] after every invocation, for metrics or custom
   * logging. Listeners run on the caller's coroutine; exceptions they throw are logged and ignored.
   */
  public fun onSummary(listener: (UseCaseSummary) -> Unit) {
    config = config.copy(summaryListeners = config.summaryListeners + listener)
  }

  /**
   * Limits how long `thenLaunch` work may run when the call passes no `timeout` of its own. The use
   * case waits for launched work before it returns, even when a step fails, so this bounds that
   * wait. Work that runs longer is cancelled and reported as a launched failure.
   */
  public fun launchTimeout(value: Duration) {
    require(value.isPositive()) { "launchTimeout must be positive" }
    config = config.copy(launchTimeout = value)
  }

  internal fun build(): UseCaseConfig = config
}

internal data class UseCaseLogMetadata(
  val eventsCount: Int,
  val workflowCount: Int,
)

internal data class UseCaseLogField(
  val name: String,
  val value: Any?,
)

private val reservedUseCaseLogFieldNames = setOf("name", "outcome", "durationMs", "events", "workflows")

/**
 * Receiver of logging field blocks. Field names must be non-blank and must not reuse the built-in
 * names `name`, `outcome`, `durationMs`, `events`, or `workflows`.
 */
@WorkflowDsl
public class UseCaseLogFieldCollector internal constructor() {
  private val fields = mutableListOf<UseCaseLogField>()

  public fun field(name: String, value: Any?) {
    require(name.isNotBlank()) { "log field name must not be blank" }
    require(name !in reservedUseCaseLogFieldNames) { "log field name is reserved: $name" }
    fields += UseCaseLogField(name, value)
  }

  internal fun toFields(): List<UseCaseLogField> = fields.toList()
}

internal class UseCaseLogging<E, S>(
  val success: List<UseCaseLogFieldCollector.(WorkflowResult<S>) -> Unit> = emptyList(),
  val failure: (UseCaseLogFieldCollector.(E) -> Unit)? = null,
)

/**
 * Receiver of [UseCaseDefinition.logging].
 */
@WorkflowDsl
public class UseCaseLoggingBuilder<E, S> internal constructor(existing: UseCaseLogging<E, S>) {
  private val success = existing.success.toMutableList()
  private var failure = existing.failure

  /** Adds fields from the final state, events, and context when the use case succeeds. */
  public fun success(fields: UseCaseLogFieldCollector.(result: WorkflowResult<S>) -> Unit) {
    success += fields
  }

  /** Adds fields from the mapped public error when the use case fails. Can be set once. */
  public fun failure(fields: UseCaseLogFieldCollector.(error: E) -> Unit) {
    check(failure == null) { "failure logging can only be configured once" }
    failure = fields
  }

  internal fun build(): UseCaseLogging<E, S> = UseCaseLogging(success.toList(), failure)
}

internal fun <E, S, O> UseCaseLogging<E, S>.fieldsFor(
  useCaseName: String,
  result: WorkflowResult<S>?,
  outcome: arrow.core.Either<E, O>,
): List<UseCaseLogField> {
  val blocks: List<UseCaseLogFieldCollector.() -> Unit> = when (outcome) {
    is arrow.core.Either.Right -> if (result == null) emptyList() else success.map { block -> { block(result) } }
    is arrow.core.Either.Left -> listOfNotNull(failure?.let { block -> { block(outcome.value) } })
  }
  return blocks.flatMap { block ->
    try {
      UseCaseLogFieldCollector().apply(block).toFields()
    } catch (ex: Exception) {
      logUseCaseProjectionFailure(useCaseName, ex)
      emptyList()
    }
  }
}

internal fun logUseCaseStarted(useCaseName: String) {
  if (useCaseLogger.isDebugEnabled) {
    useCaseLogger.debug("UseCase invocation started name={}", useCaseName)
  }
}

internal fun logUseCaseFinished(
  config: UseCaseConfig,
  useCaseName: String,
  succeeded: Boolean,
  duration: Duration,
  metadata: UseCaseLogMetadata,
  extraFields: () -> List<UseCaseLogField> = { emptyList() },
) {
  if (!useCaseLogger.isDebugEnabled) return

  val fields = buildList {
    add(UseCaseLogField("name", useCaseName))
    add(UseCaseLogField("outcome", config.renderOutcome(succeeded)))
    if (config.defaultSummaryFields == DefaultSummaryFields.Enabled) {
      add(UseCaseLogField("durationMs", duration.inWholeMilliseconds))
      add(UseCaseLogField("events", metadata.eventsCount))
      add(UseCaseLogField("workflows", metadata.workflowCount))
    }
    addAll(extraFields())
  }.associateBy { it.name }.values

  val message = "UseCase invocation finished ${fields.joinToString(" ") { "${it.name}={}" }}"
  useCaseLogger.debug(message, *fields.map { it.value }.toTypedArray())
}

private fun UseCaseConfig.renderOutcome(succeeded: Boolean): String {
  val outcome = if (succeeded) "success" else "failure"
  return when (outcomeStyle) {
    OutcomeStyle.Plain -> outcome
    OutcomeStyle.AnsiColor -> if (succeeded) "\u001B[32m$outcome\u001B[0m" else "\u001B[31m$outcome\u001B[0m"
  }
}

internal fun logUseCaseProjectionFailure(useCaseName: String, error: Throwable) {
  if (useCaseLogger.isDebugEnabled) {
    useCaseLogger.debug(
      "UseCase logging projection failed name={} error={}",
      useCaseName,
      error::class.simpleName ?: "Throwable",
    )
  }
}

internal fun notifySummaryListeners(config: UseCaseConfig, summary: UseCaseSummary) {
  config.summaryListeners.forEach { listener ->
    try {
      listener(summary)
    } catch (ex: Exception) {
      useCaseLogger.warn("UseCase summary listener failed name={} error={}", summary.useCaseName, ex::class.simpleName)
    }
  }
}
