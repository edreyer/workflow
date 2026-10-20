package io.liquidsoftware.workflow

import arrow.core.Either
import kotlin.time.Duration

/**
 * Summary of one use case invocation, for metrics or custom logging. Register a listener with
 * `useCaseConfig { onSummary { summary -> ... } }`.
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
 * @property type The class name of the raised error, or of the exception for [Failure.Unexpected].
 * @property unexpected Whether the failure was a [Failure.Unexpected].
 * @property message The raised error's `toString()` or the exception's message. Redacted (`null`)
 * unless [UseCaseSummary.withErrorMessages] was used.
 * @property workflowId The step that failed, when the failure came from a step.
 */
public data class RootErrorInfo(
  val type: String,
  val unexpected: Boolean,
  val message: String?,
  val workflowId: String?,
)

internal fun buildSummary(
  useCaseName: String,
  outcome: Either<Failure<*>, *>,
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
        type = it.typeName(),
        unexpected = it is Failure.Unexpected,
        message = null,
        workflowId = recorder.failedStepId(it),
      )
    },
    launchedFailures = finalResult?.events.orEmpty().filterIsInstance<LaunchedFailureEvent>()
      .map { "thenLaunch failure ${it.workflowId} (${it.message})" },
    correlationId = context[WorkflowContext.CORRELATION_ID],
  ).also { it.errorMessage = error?.messageText() }
}

private fun Failure<*>.typeName(): String = when (this) {
  is Failure.Raised -> error?.let { it::class.simpleName } ?: "null"
  is Failure.Unexpected -> cause::class.simpleName ?: "Throwable"
}

private fun Failure<*>.messageText(): String? = when (this) {
  is Failure.Raised -> error.toString()
  is Failure.Unexpected -> cause.message
}
