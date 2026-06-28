package io.liquidsoftware.workflow

import arrow.core.Either
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UseCaseLoggingTest {

  data class LoggingCommand(val patientName: String) : UseCaseCommand
  data class LoggingState(val patientName: String) : WorkflowState
  data class LoggingEvent(
    override val id: UUID,
    override val timestamp: Instant,
    val patientName: String,
  ) : Event

  class LoggingWorkflow : Workflow<LoggingState, LoggingState>() {
    override val id: String = "logging-workflow"

    override suspend fun executeWorkflow(
      input: LoggingState,
      context: WorkflowContext
    ): Either<WorkflowError, WorkflowResult<LoggingState>> =
      Either.Right(
        WorkflowResult(
          state = input,
          events = listOf(LoggingEvent(UUID.randomUUID(), Instant.now(), input.patientName)),
          context = context
        )
      )
  }

  class EventOnlyLoggingWorkflow : Workflow<LoggingState, LoggingState>() {
    override val id: String = "event-only-logging-workflow"

    override suspend fun executeWorkflow(
      input: LoggingState,
      context: WorkflowContext
    ): Either<WorkflowError, WorkflowResult<LoggingState>> =
      Either.Right(
        WorkflowResult(
          state = input,
          events = listOf(LoggingEvent(UUID.randomUUID(), Instant.now(), "event-${input.patientName}")),
          context = context
        )
      )
  }

  class FailingLoggingWorkflow : Workflow<LoggingState, LoggingState>() {
    override val id: String = "failing-logging-workflow"

    override suspend fun executeWorkflow(
      input: LoggingState,
      context: WorkflowContext
    ): Either<WorkflowError, WorkflowResult<LoggingState>> =
      Either.Left(WorkflowError.ExecutionError("patient payload must not appear"))
  }

  @Test
  fun `logs named use case start and successful outcome without command or event data`() {
    withUseCaseLogs { events ->
      val useCase = workflowUseCase<LoggingCommand>(name = "RegisterPatient") {
        startWith { command -> Either.Right(LoggingState(command.patientName)) }
        then(LoggingWorkflow())
      }

      val result = runBlocking { useCase.execute(LoggingCommand("Alice Sensitive")) }

      assertTrue(result.isRight())
      val messages = events.map { it.formattedMessage }
      assertEquals(2, messages.size)
      assertTrue(messages[0].contains("UseCase invocation started"))
      assertTrue(messages[0].contains("name=RegisterPatient"))
      assertTrue(messages[1].contains("UseCase invocation finished"))
      assertTrue(messages[1].contains("name=RegisterPatient"))
      assertTrue(messages[1].contains("outcome=success"))
      assertTrue(messages[1].contains("events=1"))
      assertTrue(messages[1].contains("workflows=1"))
      assertFalse(messages.any { it.contains("Alice Sensitive") })
    }
  }

  @Test
  fun `logs named use case failed outcome without command input or error message data`() {
    withUseCaseLogs { events ->
      val useCase = workflowUseCase<LoggingCommand>(name = "RegisterPatient") {
        startWith { command -> Either.Right(LoggingState(command.patientName)) }
        then(FailingLoggingWorkflow())
      }

      val result = runBlocking { useCase.execute(LoggingCommand("Bob Sensitive")) }

      assertTrue(result.isLeft())
      val messages = events.map { it.formattedMessage }
      assertEquals(2, messages.size)
      assertTrue(messages[0].contains("UseCase invocation started"))
      assertTrue(messages[0].contains("name=RegisterPatient"))
      assertTrue(messages[1].contains("UseCase invocation finished"))
      assertTrue(messages[1].contains("name=RegisterPatient"))
      assertTrue(messages[1].contains("outcome=failure"))
      assertFalse(messages.any { it.contains("Bob Sensitive") })
      assertFalse(messages.any { it.contains("patient payload must not appear") })
    }
  }

  @Test
  fun `logs projected invocation failure when projection cannot find expected event`() {
    withUseCaseLogs { events ->
      val useCase = workflowUseCase<LoggingCommand>(name = "RegisterPatient") {
        startWith { command -> Either.Right(LoggingState(command.patientName)) }
      }

      val result = runBlocking { useCase.executeForEvent<LoggingEvent>(LoggingCommand("Carol Sensitive")) }

      assertTrue(result.isLeft())
      val messages = events.map { it.formattedMessage }
      assertEquals(2, messages.size)
      assertTrue(messages[1].contains("name=RegisterPatient"))
      assertTrue(messages[1].contains("outcome=failure"))
      assertFalse(messages.any { it.contains("Carol Sensitive") })
    }
  }

  @Test
  fun `public use case logs configured name without input or output data`() {
    withUseCaseLogs { events ->
      val useCase: UseCase<LoggingCommand, WorkflowError, String> =
        useCase(name = "RegisterPatient") {
          startWith { command -> Either.Right(LoggingState(command.patientName)) }
          then(LoggingWorkflow())
          result(state<LoggingState>()) { state -> state.patientName }
        }

      val result = runBlocking { useCase(LoggingCommand("Diana Sensitive")) }

      assertTrue(result.isRight())
      val messages = events.map { it.formattedMessage }
      assertEquals(2, messages.size)
      assertTrue(messages[0].contains("name=RegisterPatient"))
      assertTrue(messages[1].contains("name=RegisterPatient"))
      assertTrue(messages[1].contains("outcome=success"))
      assertTrue(messages[1].contains("durationMs="))
      assertTrue(messages[1].contains("events=1"))
      assertTrue(messages[1].contains("workflows=1"))
      assertFalse(messages.any { it.contains("Diana Sensitive") })
    }
  }

  @Test
  fun `public use case logs failed outcome when startWith throws without input data`() {
    withUseCaseLogs { events ->
      val useCase: UseCase<LoggingCommand, WorkflowError, String> =
        useCase(name = "RegisterPatient") {
          startWith<LoggingState> { command -> error("invalid patient ${command.patientName}") }
          result(state<LoggingState>()) { state -> state.patientName }
        }

      val result = runBlocking { useCase(LoggingCommand("Eve Sensitive")) }

      assertTrue(result.isLeft())
      val messages = events.map { it.formattedMessage }
      assertEquals(2, messages.size)
      assertTrue(messages[0].contains("name=RegisterPatient"))
      assertTrue(messages[1].contains("name=RegisterPatient"))
      assertTrue(messages[1].contains("outcome=failure"))
      assertFalse(messages.any { it.contains("Eve Sensitive") })
      assertFalse(messages.any { it.contains("invalid patient") })
    }
  }

  @Test
  fun `global config can disable default summary fields`() {
    withUseCaseLogs { events ->
      useCaseLoggingGlobalConfig {
        defaultSummaryFields(DefaultSummaryFields.Disabled)
      }

      val useCase = workflowUseCase<LoggingCommand>(name = "RegisterPatient") {
        startWith { command -> Either.Right(LoggingState(command.patientName)) }
        then(LoggingWorkflow())
      }

      val result = runBlocking { useCase.execute(LoggingCommand("Alice Sensitive")) }

      assertTrue(result.isRight())
      val message = events.map { it.formattedMessage }.last()
      assertTrue(message.contains("name=RegisterPatient"))
      assertTrue(message.contains("outcome=success"))
      assertFalse(message.contains("durationMs="))
      assertFalse(message.contains("events="))
      assertFalse(message.contains("workflows="))
    }
  }

  @Test
  fun `global config can color only outcome value`() {
    withUseCaseLogs { events ->
      useCaseLoggingGlobalConfig {
        outcomeStyle(OutcomeStyle.AnsiColor)
      }

      val useCase = workflowUseCase<LoggingCommand>(name = "RegisterPatient") {
        startWith { command -> Either.Right(LoggingState(command.patientName)) }
        then(FailingLoggingWorkflow())
      }

      val result = runBlocking { useCase.execute(LoggingCommand("Bob Sensitive")) }

      assertTrue(result.isLeft())
      val message = events.map { it.formattedMessage }.last()
      assertTrue(message.contains("outcome=\u001B[31mfailure\u001B[0m"))
      assertTrue(message.contains("name=RegisterPatient"))
      assertFalse(message.contains("\u001B[31mname"))
    }
  }

  @Test
  fun `public use case logs success fields from final state`() {
    withUseCaseLogs { events ->
      val useCase: UseCase<LoggingCommand, WorkflowError, String> =
        useCase(name = "RegisterPatient") {
          startWith { command -> Either.Right(LoggingState(command.patientName)) }
          result(state<LoggingState>()) { state -> state.patientName }
          logging {
            success(state<LoggingState>()) { state ->
              field("patientLength", state.patientName.length)
            }
          }
        }

      val result = runBlocking { useCase(LoggingCommand("Diana Sensitive")) }

      assertTrue(result.isRight())
      val message = events.map { it.formattedMessage }.last()
      assertTrue(message.contains("patientLength=15"))
      assertFalse(message.contains("Diana Sensitive"))
    }
  }

  @Test
  fun `public use case logs success fields from last event`() {
    withUseCaseLogs { events ->
      val useCase: UseCase<LoggingCommand, WorkflowError, String> =
        useCase(name = "RegisterPatient") {
          startWith { command -> Either.Right(LoggingState(command.patientName)) }
          then(EventOnlyLoggingWorkflow())
          result(state<LoggingState>()) { state -> state.patientName }
          logging {
            success(event<LoggingEvent>()) { event ->
              field("eventStepLength", event.patientName.length)
            }
          }
        }

      val result = runBlocking { useCase(LoggingCommand("Diana Sensitive")) }

      assertTrue(result.isRight())
      val message = events.map { it.formattedMessage }.last()
      assertTrue(message.contains("eventStepLength=21"))
      assertFalse(message.contains("event-Diana Sensitive"))
    }
  }

  @Test
  fun `public use case logs success fields from state and event projections together`() {
    withUseCaseLogs { events ->
      val useCase: UseCase<LoggingCommand, WorkflowError, String> =
        useCase(name = "RegisterPatient") {
          startWith { command -> Either.Right(LoggingState(command.patientName)) }
          then(EventOnlyLoggingWorkflow())
          result(state<LoggingState>()) { state -> state.patientName }
          logging {
            success(state<LoggingState>()) { state ->
              field("patientLength", state.patientName.length)
            }
            success(event<LoggingEvent>()) { event ->
              field("eventStepLength", event.patientName.length)
            }
          }
        }

      val result = runBlocking { useCase(LoggingCommand("Diana Sensitive")) }

      assertTrue(result.isRight())
      val message = events.map { it.formattedMessage }.last()
      assertTrue(message.contains("patientLength=15"))
      assertTrue(message.contains("eventStepLength=21"))
      assertFalse(message.contains("Diana Sensitive"))
      assertFalse(message.contains("event-Diana Sensitive"))
    }
  }

  @Test
  fun `public use case logs failure fields from mapped typed error`() {
    data class PatientBoundaryError(val reason: String, val retryable: Boolean)

    withUseCaseLogs { events ->
      val useCase: UseCase<LoggingCommand, PatientBoundaryError, String> =
        useCase(
          name = "RegisterPatient",
          errors = { PatientBoundaryError(reason = it::class.simpleName ?: "Unknown", retryable = false) },
        ) {
          startWith { command -> Either.Right(LoggingState(command.patientName)) }
          then(FailingLoggingWorkflow())
          result(state<LoggingState>()) { state -> state.patientName }
          logging {
            failure { error ->
              field("reason", error.reason)
              field("retryable", error.retryable)
            }
          }
        }

      val result = runBlocking { useCase(LoggingCommand("Bob Sensitive")) }

      assertTrue(result.isLeft())
      val message = events.map { it.formattedMessage }.last()
      assertTrue(message.contains("reason=ExecutionContextError"))
      assertTrue(message.contains("retryable=false"))
      assertFalse(message.contains("Bob Sensitive"))
      assertFalse(message.contains("patient payload must not appear"))
    }
  }

  @Test
  fun `failure logging projection failure does not change mapped error result`() {
    data class PatientBoundaryError(val reason: String, val retryable: Boolean)

    withUseCaseLogs { events ->
      val useCase: UseCase<LoggingCommand, PatientBoundaryError, String> =
        useCase(
          name = "RegisterPatient",
          errors = { PatientBoundaryError(reason = it::class.simpleName ?: "Unknown", retryable = false) },
        ) {
          startWith { command -> Either.Right(LoggingState(command.patientName)) }
          then(FailingLoggingWorkflow())
          result(state<LoggingState>()) { state -> state.patientName }
          logging {
            failure {
              error("bad failure logger")
            }
          }
        }

      val result = runBlocking { useCase(LoggingCommand("Bob Sensitive")) }

      assertTrue(result.isLeft())
      val error = (result as Either.Left).value
      assertEquals(PatientBoundaryError(reason = "ExecutionContextError", retryable = false), error)
      val messages = events.map { it.formattedMessage }
      assertTrue(messages.any { it.contains("UseCase logging projection failed") })
      assertTrue(messages.last().contains("outcome=failure"))
      assertFalse(messages.any { it.contains("Bob Sensitive") })
      assertFalse(messages.any { it.contains("patient payload must not appear") })
      assertFalse(messages.any { it.contains("bad failure logger") })
    }
  }

  @Test
  fun `invalid failure logging field does not change mapped error result`() {
    data class PatientBoundaryError(val reason: String, val retryable: Boolean)

    listOf("", "outcome").forEach { invalidField ->
      withUseCaseLogs { events ->
        val useCase: UseCase<LoggingCommand, PatientBoundaryError, String> =
          useCase(
            name = "RegisterPatient",
            errors = { PatientBoundaryError(reason = it::class.simpleName ?: "Unknown", retryable = false) },
          ) {
            startWith { command -> Either.Right(LoggingState(command.patientName)) }
            then(FailingLoggingWorkflow())
            result(state<LoggingState>()) { state -> state.patientName }
            logging {
              failure { error ->
                field(invalidField, error.reason)
              }
            }
          }

        val result = runBlocking { useCase(LoggingCommand("Bob Sensitive")) }

        assertTrue(result.isLeft())
        val error = (result as Either.Left).value
        assertEquals(PatientBoundaryError(reason = "ExecutionContextError", retryable = false), error)
        val messages = events.map { it.formattedMessage }
        assertTrue(messages.any { it.contains("UseCase logging projection failed") })
        assertTrue(messages.last().contains("outcome=failure"))
        assertFalse(messages.last().contains("reason=ExecutionContextError"))
        assertFalse(messages.any { it.contains("Bob Sensitive") })
        assertFalse(messages.any { it.contains("patient payload must not appear") })
      }
    }
  }

  @Test
  fun `public use case emits exactly one start and one finish log when local logging is configured`() {
    withUseCaseLogs { events ->
      val useCase: UseCase<LoggingCommand, WorkflowError, String> =
        useCase(name = "RegisterPatient") {
          startWith { command -> Either.Right(LoggingState(command.patientName)) }
          result(state<LoggingState>()) { state -> state.patientName }
          logging {
            success(state<LoggingState>()) { state ->
              field("patientLength", state.patientName.length)
            }
          }
        }

      val result = runBlocking { useCase(LoggingCommand("Diana Sensitive")) }

      assertTrue(result.isRight())
      val messages = events.map { it.formattedMessage }
      assertEquals(2, messages.size)
      assertTrue(messages[0].contains("UseCase invocation started"))
      assertTrue(messages[1].contains("UseCase invocation finished"))
      assertTrue(messages[1].contains("patientLength=15"))
    }
  }

  @Test
  fun `success logging projection failure does not change result`() {
    withUseCaseLogs { events ->
      val useCase: UseCase<LoggingCommand, WorkflowError, String> =
        useCase(name = "RegisterPatient") {
          startWith { command -> Either.Right(LoggingState(command.patientName)) }
          result(state<LoggingState>()) { state -> state.patientName }
          logging {
            success(event<LoggingEvent>()) { event ->
              field("eventLength", event.patientName.length)
            }
          }
        }

      val result = runBlocking { useCase(LoggingCommand("Diana Sensitive")) }

      assertTrue(result.isRight())
      assertEquals("Diana Sensitive", (result as Either.Right).value)
      val messages = events.map { it.formattedMessage }
      assertTrue(messages.any { it.contains("UseCase logging projection failed") })
      assertTrue(messages.last().contains("outcome=success"))
      assertFalse(messages.last().contains("eventLength="))
    }
  }

  @Test
  fun `reserved local fields are rejected and canonical summary is preserved`() {
    listOf("name", "outcome", "durationMs", "events", "workflows").forEach { reservedField ->
      withUseCaseLogs { events ->
        val useCase: UseCase<LoggingCommand, WorkflowError, String> =
          useCase(name = "RegisterPatient") {
            startWith { command -> Either.Right(LoggingState(command.patientName)) }
            result(state<LoggingState>()) { state -> state.patientName }
            logging {
              success(state<LoggingState>()) {
                field(reservedField, "spoofed")
              }
            }
          }

        val result = runBlocking { useCase(LoggingCommand("Diana Sensitive")) }

        assertTrue(result.isRight())
        val messages = events.map { it.formattedMessage }
        assertTrue(messages.any { it.contains("UseCase logging projection failed") })
        val message = messages.last()
        assertTrue(message.contains("name=RegisterPatient"))
        assertTrue(message.contains("outcome=success"))
        assertTrue(message.contains("durationMs="))
        assertTrue(message.contains("events=0"))
        assertTrue(message.contains("workflows=0"))
        assertFalse(message.contains("$reservedField=spoofed"))
      }
    }
  }

  @Test
  fun `logging fields are not evaluated when debug logging is disabled`() {
    val logger = LoggerFactory.getLogger("io.liquidsoftware.workflow.UseCase") as Logger
    val previousLevel = logger.level
    val previousAdditive = logger.isAdditive
    var successFieldsEvaluated = false
    var failureFieldsEvaluated = false

    resetUseCaseLoggingGlobalConfigForTest()
    logger.level = Level.INFO
    logger.isAdditive = false
    try {
      val successUseCase: UseCase<LoggingCommand, WorkflowError, String> =
        useCase(name = "RegisterPatient") {
          startWith { command -> Either.Right(LoggingState(command.patientName)) }
          result(state<LoggingState>()) { state -> state.patientName }
          logging {
            success(state<LoggingState>()) {
              successFieldsEvaluated = true
              field("patientLength", it.patientName.length)
            }
          }
        }

      val failureUseCase: UseCase<LoggingCommand, WorkflowError, String> =
        useCase(name = "RegisterPatient") {
          startWith { command -> Either.Right(LoggingState(command.patientName)) }
          then(FailingLoggingWorkflow())
          result(state<LoggingState>()) { state -> state.patientName }
          logging {
            failure {
              failureFieldsEvaluated = true
              field("errorType", it::class.simpleName)
            }
          }
        }

      val successResult = runBlocking { successUseCase(LoggingCommand("Diana Sensitive")) }
      val failureResult = runBlocking { failureUseCase(LoggingCommand("Bob Sensitive")) }

      assertTrue(successResult.isRight())
      assertTrue(failureResult.isLeft())
      assertFalse(successFieldsEvaluated)
      assertFalse(failureFieldsEvaluated)
    } finally {
      resetUseCaseLoggingGlobalConfigForTest()
      logger.level = previousLevel
      logger.isAdditive = previousAdditive
    }
  }

  private fun withUseCaseLogs(assertions: (List<ILoggingEvent>) -> Unit) {
    val logger = LoggerFactory.getLogger("io.liquidsoftware.workflow.UseCase") as Logger
    val previousLevel = logger.level
    val previousAdditive = logger.isAdditive
    val appender = ListAppender<ILoggingEvent>()
    appender.start()

    resetUseCaseLoggingGlobalConfigForTest()
    logger.level = Level.DEBUG
    logger.isAdditive = false
    logger.addAppender(appender)
    try {
      assertions(appender.list)
    } finally {
      resetUseCaseLoggingGlobalConfigForTest()
      logger.detachAppender(appender)
      logger.level = previousLevel
      logger.isAdditive = previousAdditive
      appender.stop()
    }
  }
}
