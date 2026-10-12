package io.liquidsoftware.workflow

import arrow.core.Either
import org.slf4j.LoggerFactory
import java.time.Duration

private val useCaseLogger = LoggerFactory.getLogger("io.liquidsoftware.workflow.UseCase")

enum class OutcomeStyle {
  Plain,
  AnsiColor
}

enum class DefaultSummaryFields {
  Disabled,
  Enabled
}

internal data class UseCaseLoggingGlobalConfig(
  val outcomeStyle: OutcomeStyle = OutcomeStyle.Plain,
  val defaultSummaryFields: DefaultSummaryFields = DefaultSummaryFields.Enabled,
)

class UseCaseLoggingGlobalConfigBuilder internal constructor(
  private var outcomeStyle: OutcomeStyle,
  private var defaultSummaryFields: DefaultSummaryFields,
) {
  fun outcomeStyle(value: OutcomeStyle) {
    outcomeStyle = value
  }

  fun defaultSummaryFields(value: DefaultSummaryFields) {
    defaultSummaryFields = value
  }

  internal fun build(): UseCaseLoggingGlobalConfig =
    UseCaseLoggingGlobalConfig(
      outcomeStyle = outcomeStyle,
      defaultSummaryFields = defaultSummaryFields,
    )
}

private val defaultUseCaseLoggingGlobalConfig = UseCaseLoggingGlobalConfig()

@Volatile
private var currentUseCaseLoggingGlobalConfig = defaultUseCaseLoggingGlobalConfig

fun useCaseLoggingGlobalConfig(configure: UseCaseLoggingGlobalConfigBuilder.() -> Unit) {
  val current = currentUseCaseLoggingGlobalConfig
  currentUseCaseLoggingGlobalConfig = UseCaseLoggingGlobalConfigBuilder(
    outcomeStyle = current.outcomeStyle,
    defaultSummaryFields = current.defaultSummaryFields,
  ).apply(configure).build()
}

internal fun resetUseCaseLoggingGlobalConfigForTest() {
  currentUseCaseLoggingGlobalConfig = defaultUseCaseLoggingGlobalConfig
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

@WorkflowDsl
class UseCaseLogFieldCollector internal constructor() {
  private val fields = mutableListOf<UseCaseLogField>()

  fun field(name: String, value: Any?) {
    require(name.isNotBlank()) { "log field name must not be blank" }
    require(name !in reservedUseCaseLogFieldNames) { "log field name is reserved: $name" }
    fields += UseCaseLogField(name, value)
  }

  internal fun toFields(): List<UseCaseLogField> = fields.toList()
}

internal fun collectUseCaseLogFields(
  block: UseCaseLogFieldCollector.() -> Unit
): List<UseCaseLogField> {
  val collector = UseCaseLogFieldCollector()
  collector.block()
  return collector.toFields()
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
  if (!useCaseLogger.isDebugEnabled) {
    return
  }

  val config = currentUseCaseLoggingGlobalConfig
  val baseFields = mutableListOf(
    UseCaseLogField("name", useCaseName),
    UseCaseLogField("outcome", config.renderOutcome(succeeded)),
  )

  if (config.defaultSummaryFields == DefaultSummaryFields.Enabled) {
    baseFields += UseCaseLogField("durationMs", duration.toMillis())
    baseFields += UseCaseLogField("events", metadata.eventsCount)
    baseFields += UseCaseLogField("workflows", metadata.workflowCount)
  }

  val fields = mergeFields(baseFields + extraFields())
  val message = "UseCase invocation finished ${fields.joinToString(" ") { "${it.name}={}" }}"
  useCaseLogger.debug(message, *fields.map { it.value }.toTypedArray())
}

private fun UseCaseLoggingGlobalConfig.renderOutcome(succeeded: Boolean): String {
  val outcome = if (succeeded) "success" else "failure"
  return when (outcomeStyle) {
    OutcomeStyle.Plain -> outcome
    OutcomeStyle.AnsiColor -> if (succeeded) "\u001B[32m$outcome\u001B[0m" else "\u001B[31m$outcome\u001B[0m"
  }
}

internal fun mergeFields(fields: List<UseCaseLogField>): List<UseCaseLogField> {
  val merged = linkedMapOf<String, UseCaseLogField>()
  fields.forEach { field -> merged[field.name] = field }
  return merged.values.toList()
}

internal fun logUseCaseProjectionFailure(
  useCaseName: String,
  projection: String,
  error: Throwable,
) {
  if (useCaseLogger.isDebugEnabled) {
    useCaseLogger.debug(
      "UseCase logging projection failed name={} projection={} error={}",
      useCaseName,
      projection,
      error::class.simpleName ?: "Throwable",
    )
  }
}

internal fun Either<WorkflowError, UseCaseResult<WorkflowState>>.logMetadata(): UseCaseLogMetadata =
  fold(
    { error -> UseCaseLogMetadata(eventsCount = 0, workflowCount = error.workflowExecutionCount()) },
    { result -> UseCaseLogMetadata(eventsCount = result.events.size, workflowCount = result.context.executions.size) }
  )

private fun WorkflowError.workflowExecutionCount(): Int =
  when (this) {
    is WorkflowError.ExecutionContextError -> 1
    is WorkflowError.ChainError -> error.workflowExecutionCount()
    else -> 0
  }
