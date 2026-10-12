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

internal data class UseCaseGlobalConfig(
  val outcomeStyle: OutcomeStyle = OutcomeStyle.Plain,
  val defaultSummaryFields: DefaultSummaryFields = DefaultSummaryFields.Enabled,
  val summaryListeners: List<(UseCaseSummary) -> Unit> = emptyList(),
)

/**
 * Receiver of [useCaseLoggingGlobalConfig].
 */
@WorkflowDsl
public class UseCaseLoggingGlobalConfigBuilder internal constructor(private var config: UseCaseGlobalConfig) {
  /** Plain `outcome=success`, or ANSI-colored outcomes for local console logs. */
  public fun outcomeStyle(value: OutcomeStyle) {
    config = config.copy(outcomeStyle = value)
  }

  /** Whether finish logs include `durationMs`, `events`, and `workflows`. */
  public fun defaultSummaryFields(value: DefaultSummaryFields) {
    config = config.copy(defaultSummaryFields = value)
  }

  /**
   * Calls [listener] with a [UseCaseSummary] after every use case invocation, for metrics or custom
   * logging. Listeners run on the caller's coroutine; exceptions they throw are logged and ignored.
   */
  public fun onSummary(listener: (UseCaseSummary) -> Unit) {
    config = config.copy(summaryListeners = config.summaryListeners + listener)
  }

  /** Removes every summary listener registered so far. */
  public fun clearSummaryListeners() {
    config = config.copy(summaryListeners = emptyList())
  }

  internal fun build(): UseCaseGlobalConfig = config
}

@Volatile
private var currentConfig = UseCaseGlobalConfig()

/**
 * Configures use case logging and summary listeners once at application startup.
 */
public fun useCaseLoggingGlobalConfig(configure: UseCaseLoggingGlobalConfigBuilder.() -> Unit) {
  currentConfig = UseCaseLoggingGlobalConfigBuilder(currentConfig).apply(configure).build()
}

internal fun resetUseCaseLoggingGlobalConfigForTest() {
  currentConfig = UseCaseGlobalConfig()
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
  useCaseName: String,
  succeeded: Boolean,
  duration: Duration,
  metadata: UseCaseLogMetadata,
  extraFields: () -> List<UseCaseLogField> = { emptyList() },
) {
  if (!useCaseLogger.isDebugEnabled) return

  val config = currentConfig
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

private fun UseCaseGlobalConfig.renderOutcome(succeeded: Boolean): String {
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

internal fun notifySummaryListeners(summary: UseCaseSummary) {
  currentConfig.summaryListeners.forEach { listener ->
    try {
      listener(summary)
    } catch (ex: Exception) {
      useCaseLogger.warn("UseCase summary listener failed name={} error={}", summary.useCaseName, ex::class.simpleName)
    }
  }
}
