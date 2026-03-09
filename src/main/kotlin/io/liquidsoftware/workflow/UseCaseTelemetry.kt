package io.liquidsoftware.workflow

import arrow.core.Either
import java.time.Duration

/**
 * Summary of a use case execution suitable for logging or metrics.
 */
data class UseCaseSummary(
  val useCaseName: String,
  val succeeded: Boolean,
  val duration: Duration?,
  val eventsCount: Int,
  val executions: List<WorkflowExecution>,
  val rootError: RootErrorInfo? = null,
  val launchedFailures: List<String> = emptyList(),
  val correlationId: String? = null
)

data class RootErrorInfo(
  val type: String,
  val message: String?,
  val workflowId: String?
)

@JvmName("toSummaryDetailed")
fun Either<WorkflowError, UseCaseResult<WorkflowState>>.toSummary(
  useCaseName: String,
  correlationId: String? = null,
  launchedFailures: List<String>? = null,
  executions: List<WorkflowExecution>? = null,
  duration: Duration? = null
): UseCaseSummary {
  return when (this) {
    is Either.Right -> {
      val resolvedCorrelationId = correlationId ?: value.context.get(WorkflowContext.CORRELATION_ID)
      val resolvedLaunchedFailures = launchedFailures ?: value.context.get(WorkflowContext.LAUNCHED_FAILURES) ?: emptyList()
      val resolvedExecutions = executions ?: value.context.executions

      UseCaseSummary(
        useCaseName = useCaseName,
        succeeded = true,
        duration = duration ?: calculateDuration(resolvedExecutions),
        eventsCount = value.events.size,
        executions = resolvedExecutions,
        launchedFailures = resolvedLaunchedFailures,
        correlationId = resolvedCorrelationId
      )
    }
    is Either.Left -> failureSummary(
      useCaseName = useCaseName,
      error = value,
      launchedFailures = launchedFailures ?: emptyList(),
      executions = executions ?: emptyList(),
      correlationId = correlationId,
      duration = duration
    )
  }
}

/**
 * Build a summary from a use case result. Provide the use case name for clarity in logs/metrics.
 * Optionally pass a correlationId previously stored in context via [WorkflowContext.CORRELATION_ID].
 */
fun Either<WorkflowError, UseCaseEvents>.toSummary(
  useCaseName: String,
  correlationId: String? = null,
  launchedFailures: List<String> = emptyList(),
  executions: List<WorkflowExecution> = emptyList(),
  duration: Duration? = null
): UseCaseSummary {
  return when (this) {
    is Either.Right -> {
      UseCaseSummary(
        useCaseName = useCaseName,
        succeeded = true,
        duration = duration,
        eventsCount = this.value.events.size,
        executions = executions,
        launchedFailures = launchedFailures,
        correlationId = correlationId
      )
    }
    is Either.Left -> failureSummary(
      useCaseName = useCaseName,
      error = value,
      launchedFailures = launchedFailures,
      executions = executions,
      correlationId = correlationId,
      duration = duration
    )
  }
}

private fun failureSummary(
  useCaseName: String,
  error: WorkflowError,
  launchedFailures: List<String>,
  executions: List<WorkflowExecution>,
  correlationId: String?,
  duration: Duration?
): UseCaseSummary {
  val info = extractRootError(error)
  val contextExec = (error as? WorkflowError.ExecutionContextError)?.execution
  val resolvedExecutions = if (executions.isNotEmpty()) executions else listOfNotNull(contextExec)
  val resolvedDuration = duration ?: calculateDuration(resolvedExecutions)

  return UseCaseSummary(
    useCaseName = useCaseName,
    succeeded = false,
    duration = resolvedDuration,
    eventsCount = 0,
    executions = resolvedExecutions,
    rootError = info,
    launchedFailures = launchedFailures,
    correlationId = correlationId
  )
}

private fun calculateDuration(executions: List<WorkflowExecution>): Duration? {
  if (executions.isEmpty()) {
    return null
  }

  val start = executions.minOf { it.startTime }
  val end = executions.maxOf { it.endTime }
  return Duration.between(start, end)
}

private fun extractRootError(error: WorkflowError): RootErrorInfo {
  return when (error) {
    is WorkflowError.ExecutionContextError -> RootErrorInfo(
      type = error.error::class.simpleName ?: "ExecutionContextError",
      message = when (val inner = error.error) {
        is WorkflowError.ValidationError -> inner.message
        is WorkflowError.ExecutionError -> inner.message
        is WorkflowError.DomainError -> "[${inner.code}] ${inner.message}"
        is WorkflowError.ExceptionError -> inner.message
        is WorkflowError.CompositionError -> inner.message
        is WorkflowError.ChainError -> inner.error.toString()
        else -> inner.toString()
      },
      workflowId = error.execution.workflowId
    )
    is WorkflowError.ChainError -> extractRootError(error.error)
    is WorkflowError.ValidationError -> RootErrorInfo("ValidationError", error.message, null)
    is WorkflowError.ExecutionError -> RootErrorInfo("ExecutionError", error.message, null)
    is WorkflowError.DomainError -> RootErrorInfo("DomainError", "[${error.code}] ${error.message}", null)
    is WorkflowError.ExceptionError -> RootErrorInfo("ExceptionError", error.message, null)
    is WorkflowError.CompositionError -> RootErrorInfo("CompositionError", error.message, null)
  }
}
