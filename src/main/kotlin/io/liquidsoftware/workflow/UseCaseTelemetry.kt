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

/**
 * Build a summary from a use case result. Provide the use case name for clarity in logs/metrics.
 * Optionally pass a correlationId previously stored in context (data["correlationId"]).
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
    is Either.Left -> {
      val info = extractRootError(this.value)
      val contextExec = (this.value as? WorkflowError.ExecutionContextError)?.execution
      val execs = if (executions.isNotEmpty()) executions else listOfNotNull(contextExec)
      val dur = duration ?: contextExec?.let { Duration.between(it.startTime, it.endTime) }
      UseCaseSummary(
        useCaseName = useCaseName,
        succeeded = false,
        duration = dur,
        eventsCount = 0,
        executions = execs,
        rootError = info,
        launchedFailures = launchedFailures,
        correlationId = correlationId
      )
    }
  }
}

private fun extractRootError(error: WorkflowError): RootErrorInfo {
  return when (error) {
    is WorkflowError.ExecutionContextError -> RootErrorInfo(
      type = error.error::class.simpleName ?: "ExecutionContextError",
      message = when (val inner = error.error) {
        is WorkflowError.ValidationError -> inner.message
        is WorkflowError.ExecutionError -> inner.message
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
    is WorkflowError.ExceptionError -> RootErrorInfo("ExceptionError", error.message, null)
    is WorkflowError.CompositionError -> RootErrorInfo("CompositionError", error.message, null)
  }
}
