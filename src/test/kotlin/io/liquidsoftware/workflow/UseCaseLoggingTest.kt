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

  private fun withUseCaseLogs(assertions: (List<ILoggingEvent>) -> Unit) {
    val logger = LoggerFactory.getLogger("io.liquidsoftware.workflow.UseCase") as Logger
    val previousLevel = logger.level
    val previousAdditive = logger.isAdditive
    val appender = ListAppender<ILoggingEvent>()
    appender.start()

    logger.level = Level.DEBUG
    logger.isAdditive = false
    logger.addAppender(appender)
    try {
      assertions(appender.list)
    } finally {
      logger.detachAppender(appender)
      logger.level = previousLevel
      logger.isAdditive = previousAdditive
      appender.stop()
    }
  }
}
