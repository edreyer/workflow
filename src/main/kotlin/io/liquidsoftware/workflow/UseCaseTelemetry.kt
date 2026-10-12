package io.liquidsoftware.workflow

import arrow.core.Either
import kotlin.time.Duration

/**
 * Summary of one use case invocation, for metrics or custom logging. Register a listener with
 * `useCaseLoggingGlobalConfig { onSummary { summary -> ... } }`.
 *
 * Error messages are redacted unless [withErrorMessages] is used.
 */
public data class UseCaseSummary(
  val useCaseName: String,
  val succeeded: Boolean,
  val duration: Duration,
  val eventsCount: Int,
  val executions: List<WorkflowExecution>,
  val rootError: RootErrorInfo? = null,
  val launchedFailures: List<String> = emptyList(),
  val correlationId: String? = null,
) {
  // Kept out of the constructor, equals, and toString so it cannot leak by accident.
  internal var errorMessage: String? = null

  /**
   * Returns a copy whose [rootError] includes the error message. Only use this when messages are
   * known to be free of user data.
   */
  public fun withErrorMessages(): UseCaseSummary =
    copy(rootError = rootError?.copy(message = errorMessage)).also { it.errorMessage = errorMessage }
}

/**
 * The error a failed invocation ended with.
 *
 * @property type The error's class name.
 * @property code [WorkflowError.code], a stable non-sensitive identifier.
 * @property message Redacted (`null`) unless [UseCaseSummary.withErrorMessages] was used.
 * @property workflowId The step that failed, when the failure came from a step.
 */
public data class RootErrorInfo(
  val type: String,
  val code: String,
  val message: String?,
  val workflowId: String?,
)

internal fun buildSummary(
  useCaseName: String,
  outcome: Either<WorkflowError, *>,
  finalResult: WorkflowResult<*>?,
  recorder: ExecutionRecorder,
  initialContext: WorkflowContext,
  duration: Duration,
): UseCaseSummary {
  val executions = recorder.snapshot()
  val context = finalResult?.context ?: initialContext
  val error = outcome.leftOrNull()
  return UseCaseSummary(
    useCaseName = useCaseName,
    succeeded = error == null,
    duration = duration,
    eventsCount = finalResult?.events?.size ?: 0,
    executions = executions,
    rootError = error?.let {
      RootErrorInfo(
        type = it::class.simpleName ?: "WorkflowError",
        code = it.code,
        message = null,
        workflowId = recorder.failedStepId(),
      )
    },
    launchedFailures = context[WorkflowContext.LAUNCHED_FAILURES].orEmpty(),
    correlationId = context[WorkflowContext.CORRELATION_ID],
  ).also { it.errorMessage = error?.message }
}
