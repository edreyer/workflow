package io.liquidsoftware.workflow

import arrow.core.Either
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UseCaseLoggingTest {

  data class RegisterPatient(val patientName: String)
  data class Patient(val patientName: String)
  data class PatientRegistered(val patientName: String) : BaseEvent()
  data class PatientError(val reason: String, val retryable: Boolean)
  data class StorageDown(val detail: String)

  private val register = workflow<Patient, Patient, Nothing>("register-patient") { patient ->
    emit(PatientRegistered(patient.patientName))
    patient
  }

  private val failing = workflow<Patient, Patient, StorageDown>("failing") {
    raise(StorageDown("patient payload must not appear"))
  }

  private fun registerPatient(
    vararg steps: Workflow<Patient, Patient, StorageDown>,
    config: UseCaseConfig = UseCaseConfig.Default,
    logging: UseCaseLoggingBuilder<Failure<StorageDown>, Patient>.() -> Unit = {},
  ): UseCase<RegisterPatient, PatientError, Int> =
    useCase(
      name = "RegisterPatient",
      errors = { failure: Failure<StorageDown> ->
        PatientError(if (failure is Failure.Raised) "storage-down" else "unexpected", retryable = false)
      },
      config = config,
    ) {
      steps.fold(startWith { command -> Patient(command.patientName) }) { pipeline, step -> pipeline.then(step) }
        .result { it.patientName.length }
        .logging(logging)
    }

  @Test
  fun `logs start and success with safe metadata only`() = runTest {
    withUseCaseLogs { events ->
      val result = registerPatient(register)(RegisterPatient("Alice Sensitive"))

      assertTrue(result.isRight())
      val messages = events.map { it.formattedMessage }
      assertEquals(2, messages.size)
      assertTrue(messages[0].contains("UseCase invocation started name=RegisterPatient"))
      assertTrue(messages[1].contains("UseCase invocation finished name=RegisterPatient outcome=success"))
      assertTrue(messages[1].contains("events=1"))
      assertTrue(messages[1].contains("workflows=1"))
      assertFalse(messages.any { it.contains("Alice Sensitive") })
    }
  }

  @Test
  fun `logs failure without input or error message`() = runTest {
    withUseCaseLogs { events ->
      val result = registerPatient(failing)(RegisterPatient("Bob Sensitive"))

      assertTrue(result.isLeft())
      val messages = events.map { it.formattedMessage }
      assertTrue(messages.last().contains("outcome=failure"))
      assertTrue(messages.last().contains("workflows=1"))
      assertFalse(messages.any { it.contains("Bob Sensitive") || it.contains("patient payload") })
    }
  }

  @Test
  fun `config can disable default summary fields and color the outcome`() = runTest {
    withUseCaseLogs { events ->
      val config = useCaseConfig {
        defaultSummaryFields(DefaultSummaryFields.Disabled)
        outcomeStyle(OutcomeStyle.AnsiColor)
      }

      registerPatient(register, config = config)(RegisterPatient("Alice"))

      val message = events.last().formattedMessage
      assertTrue(message.contains("outcome=\u001B[32msuccess\u001B[0m"))
      assertFalse(message.contains("durationMs="))
      assertFalse(message.contains("events="))
    }
  }

  @Test
  fun `success fields are typed to the final result`() = runTest {
    withUseCaseLogs { events ->
      val useCase = registerPatient(register) {
        success { (state, events) ->
          field("nameLength", state.patientName.length)
          field("registered", events.lastEvent<PatientRegistered>() != null)
        }
      }

      useCase(RegisterPatient("Diana Sensitive"))

      assertEquals(2, events.size)
      assertTrue(events.last().formattedMessage.endsWith("nameLength=15 registered=true"))
    }
  }

  @Test
  fun `failure fields are typed to the failure`() = runTest {
    withUseCaseLogs { events ->
      val useCase = registerPatient(failing) {
        failure { failure ->
          field("reason", if (failure is Failure.Raised) failure.error::class.simpleName else "unexpected")
          field("unexpected", failure is Failure.Unexpected)
        }
      }

      val result = useCase(RegisterPatient("Bob"))

      assertEquals(Either.Left(PatientError("storage-down", false)), result)
      assertTrue(events.last().formattedMessage.endsWith("reason=StorageDown unexpected=false"))
    }
  }

  @Test
  fun `a failing field block is reported and does not change the result`() = runTest {
    withUseCaseLogs { events ->
      val useCase = registerPatient(register) {
        success { error("cannot collect") }
        success { field("kept", true) }
      }

      val result = useCase(RegisterPatient("Diana"))

      assertEquals(Either.Right(5), result)
      val messages = events.map { it.formattedMessage }
      assertTrue(messages.any { it.contains("UseCase logging projection failed name=RegisterPatient") })
      assertTrue(messages.last().contains("outcome=success"))
      assertTrue(messages.last().endsWith("kept=true"))
    }
  }

  @Test
  fun `reserved field names are rejected and the canonical fields are preserved`() = runTest {
    listOf("name", "outcome", "durationMs", "events", "workflows").forEach { reserved ->
      withUseCaseLogs { events ->
        val useCase = registerPatient(register) { success { field(reserved, "spoofed") } }

        assertTrue(useCase(RegisterPatient("Diana")).isRight())
        val message = events.last().formattedMessage
        assertTrue(message.contains("name=RegisterPatient outcome=success"))
        assertFalse(message.contains("$reserved=spoofed"))
      }
    }
  }

  @Test
  fun `field blocks are not evaluated when debug logging is disabled`() = runTest {
    val logger = LoggerFactory.getLogger("io.liquidsoftware.workflow.UseCase") as Logger
    val previousLevel = logger.level
    var evaluated = false
    logger.level = Level.INFO
    try {
      val succeeding = registerPatient(register) { success { evaluated = true } }
      val failingUseCase = registerPatient(failing) { failure { evaluated = true } }
      succeeding(RegisterPatient("Diana"))
      failingUseCase(RegisterPatient("Diana"))
      assertFalse(evaluated)
    } finally {
      logger.level = previousLevel
    }
  }

  private inline fun withUseCaseLogs(assertions: (List<ILoggingEvent>) -> Unit) {
    val logger = LoggerFactory.getLogger("io.liquidsoftware.workflow.UseCase") as Logger
    val previousLevel = logger.level
    val previousAdditive = logger.isAdditive
    val appender = ListAppender<ILoggingEvent>().apply { start() }

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
