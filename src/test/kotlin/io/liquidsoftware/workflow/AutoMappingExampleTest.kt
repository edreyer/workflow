package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoMappingExampleTest {

  @Test
  fun `should demonstrate auto-mapping with first method using property map`() = runBlocking {
    // Given
    val initialCommand = RegisterCustomerCommand("test@example.com", "John Doe")

    val useCase = useCase<RegisterCustomerCommand> {
      // Auto-mapping for first workflow with property map
      first(validateCustomerWorkflow, mapOf(
        "email" to "email",
        "name" to "name"
      ))

      // Auto-mapping - fields match directly with ValidatedCustomerEvent
      this.then(createCustomerWorkflow)
    }

    // When
    val result = useCase.execute(initialCommand)

    // Then
    assertTrue(result.isRight())
    val events = (result as Either.Right).value.events
    assertEquals(2, events.size)
    assertTrue(events.any { it is ValidatedCustomerEvent })
    assertTrue(events.any { it is CustomerCreatedEvent })
  }

  @Test
  fun `should demonstrate auto-mapping with first method using builder pattern`() = runBlocking {
    // Given
    val initialCommand = RegisterCustomerCommand("test@example.com", "John Doe")

    val useCase = useCase<RegisterCustomerCommand> {
      // Auto-mapping for first workflow with builder pattern
      first(validateCustomerWorkflow) {
        "email" from "email"
        "name" from "name"
      }

      // Auto-mapping with property name mapping
      this.then(sendWelcomeEmailWorkflow, mapOf(
        "recipientEmail" to "email",
        "recipientName" to "name"
      ))
    }

    // When
    val result = useCase.execute(initialCommand)

    // Then
    assertTrue(result.isRight())
    val events = (result as Either.Right).value.events
    assertEquals(2, events.size)
    assertTrue(events.any { it is ValidatedCustomerEvent })
    assertTrue(events.any { it is WelcomeEmailSentEvent })
  }

  @Test
  fun `should demonstrate auto-mapping between workflows`() = runBlocking {
    // Given
    val customerId = UUID.randomUUID()
    val initialCommand = RegisterCustomerCommand("test@example.com", "John Doe")

    val useCase = useCase<RegisterCustomerCommand> {
      // Auto-mapping for first workflow with property map
      first(validateCustomerWorkflow, mapOf(
        "email" to "email",
        "name" to "name"
      ))

      // Auto-mapping - fields match directly with ValidatedCustomerEvent
      this.then(createCustomerWorkflow)

      // Auto-mapping with property name mapping
      this.then(sendWelcomeEmailWorkflow, mapOf(
        "recipientEmail" to "email",
        "recipientName" to "name"
      ))

      // Auto-mapping using builder pattern
      then(storeCustomerWorkflow) {
        "customerEmail" from "email"
        "customerName" from "name"
        "customerId" from "id"
      }
    }

    // When
    val result = useCase.execute(initialCommand)

    // Then
    assertTrue(result.isRight())
    val events = (result as Either.Right).value.events
    assertEquals(4, events.size)
    assertTrue(events.any { it is ValidatedCustomerEvent })
    assertTrue(events.any { it is CustomerCreatedEvent })
    assertTrue(events.any { it is WelcomeEmailSentEvent })
    assertTrue(events.any { it is CustomerStoredEvent })
  }

  // Sample data classes for the test
  data class RegisterCustomerCommand(val email: String, val name: String) : UseCaseCommand

  data class ValidateCustomerInput(val email: String, val name: String) : WorkflowCommand

  data class CreateCustomerInput(val email: String, val name: String) : WorkflowCommand

  data class SendWelcomeEmailInput(val recipientEmail: String, val recipientName: String) : WorkflowCommand

  data class StoreCustomerInput(val customerEmail: String, val customerName: String, val customerId: UUID) : WorkflowCommand

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

  // Sample workflows
  val validateCustomerWorkflow = object : Workflow<ValidateCustomerInput, ValidatedCustomerEvent>() {
    override val id = "validate-customer"

    override suspend fun executeWorkflow(input: ValidateCustomerInput): Either<WorkflowError, WorkflowResult> = either {
      val event = ValidatedCustomerEvent(email = input.email, name = input.name)
      WorkflowResult(listOf(event))
    }
  }

  val createCustomerWorkflow = object : Workflow<CreateCustomerInput, CustomerCreatedEvent>() {
    override val id = "create-customer"

    override suspend fun executeWorkflow(input: CreateCustomerInput): Either<WorkflowError, WorkflowResult> = either {
      val event = CustomerCreatedEvent(
        email = input.email,
        name = input.name,
        accountId = UUID.randomUUID()
      )
      WorkflowResult(listOf(event))
    }
  }

  val sendWelcomeEmailWorkflow = object : Workflow<SendWelcomeEmailInput, WelcomeEmailSentEvent>() {
    override val id = "send-welcome-email"

    override suspend fun executeWorkflow(input: SendWelcomeEmailInput): Either<WorkflowError, WorkflowResult> = either {
      val event = WelcomeEmailSentEvent(recipientEmail = input.recipientEmail)
      WorkflowResult(listOf(event))
    }
  }

  val storeCustomerWorkflow = object : Workflow<StoreCustomerInput, CustomerStoredEvent>() {
    override val id = "store-customer"

    override suspend fun executeWorkflow(input: StoreCustomerInput): Either<WorkflowError, WorkflowResult> = either {
      val event = CustomerStoredEvent(
        customerEmail = input.customerEmail,
        customerId = input.customerId
      )
      WorkflowResult(listOf(event))
    }
  }
}
