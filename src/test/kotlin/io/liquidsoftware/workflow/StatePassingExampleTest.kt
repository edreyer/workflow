package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatePassingExampleTest {

  @Test
  fun `should execute a state passing pipeline`() = runBlocking {
    val useCase = useCase<RegisterCustomerCommand> {
      startWith { command -> Either.Right(RegistrationState(command.email, command.name)) }
      then(validateCustomerWorkflow)
      then(createCustomerWorkflow)
      then(sendWelcomeEmailWorkflow)
      then(storeCustomerWorkflow)
    }

    val result = useCase.execute(RegisterCustomerCommand("test@example.com", "John Doe"))

    assertTrue(result.isRight())
    val events = (result as Either.Right).value.events
    assertEquals(4, events.size)
    assertTrue(events.any { it is ValidatedCustomerEvent })
    assertTrue(events.any { it is CustomerCreatedEvent })
    assertTrue(events.any { it is WelcomeEmailSentEvent })
    assertTrue(events.any { it is CustomerStoredEvent })
  }

  data class RegisterCustomerCommand(val email: String, val name: String) : UseCaseCommand

  data class RegistrationState(val email: String, val name: String) : WorkflowState
  data class ValidatedState(val email: String, val name: String) : WorkflowState
  data class CreatedState(val email: String, val name: String, val customerId: UUID) : WorkflowState

  data class ValidatedCustomerEvent(
    override val id: UUID = UUID.randomUUID(),
    override val timestamp: Instant = Instant.now(),
    val email: String,
    val name: String
  ) : Event

  data class CustomerCreatedEvent(
    override val id: UUID = UUID.randomUUID(),
    override val timestamp: Instant = Instant.now(),
    val email: String,
    val name: String,
    val accountId: UUID
  ) : Event

  data class WelcomeEmailSentEvent(
    override val id: UUID = UUID.randomUUID(),
    override val timestamp: Instant = Instant.now(),
    val recipientEmail: String
  ) : Event

  data class CustomerStoredEvent(
    override val id: UUID = UUID.randomUUID(),
    override val timestamp: Instant = Instant.now(),
    val customerEmail: String,
    val customerId: UUID
  ) : Event

  val validateCustomerWorkflow = object : Workflow<RegistrationState, ValidatedState>() {
    override val id = "validate-customer"

    override suspend fun executeWorkflow(
      input: RegistrationState
    ): Either<WorkflowError, WorkflowResult<ValidatedState>> = either {
      val event = ValidatedCustomerEvent(email = input.email, name = input.name)
      WorkflowResult(ValidatedState(input.email, input.name), listOf(event))
    }
  }

  val createCustomerWorkflow = object : Workflow<ValidatedState, CreatedState>() {
    override val id = "create-customer"

    override suspend fun executeWorkflow(
      input: ValidatedState
    ): Either<WorkflowError, WorkflowResult<CreatedState>> = either {
      val event = CustomerCreatedEvent(
        email = input.email,
        name = input.name,
        accountId = UUID.randomUUID()
      )
      WorkflowResult(
        CreatedState(input.email, input.name, event.accountId),
        listOf(event)
      )
    }
  }

  val sendWelcomeEmailWorkflow = object : Workflow<CreatedState, CreatedState>() {
    override val id = "send-welcome-email"

    override suspend fun executeWorkflow(
      input: CreatedState
    ): Either<WorkflowError, WorkflowResult<CreatedState>> = either {
      val event = WelcomeEmailSentEvent(recipientEmail = input.email)
      WorkflowResult(input, listOf(event))
    }
  }

  val storeCustomerWorkflow = object : Workflow<CreatedState, CreatedState>() {
    override val id = "store-customer"

    override suspend fun executeWorkflow(
      input: CreatedState
    ): Either<WorkflowError, WorkflowResult<CreatedState>> = either {
      val event = CustomerStoredEvent(
        customerEmail = input.email,
        customerId = input.customerId
      )
      WorkflowResult(input, listOf(event))
    }
  }
}
