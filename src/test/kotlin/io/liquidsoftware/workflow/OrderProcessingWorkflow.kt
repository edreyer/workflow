package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID

// Example Usage
fun main() {

  // -------------------------
  // Setup example domain data
  // -------------------------
  val orderId = UUID.randomUUID()
  val customerId = UUID.randomUUID()
  val items = listOf(
    OrderItem(UUID.randomUUID(), 2, 29.99),
    OrderItem(UUID.randomUUID(), 1, 49.99)
  )
  val totalAmount = items.sumOf { it.quantity * it.price }

  // -------------------------
  // Assemble UseCase
  // -------------------------

  data class ProcessOrderCommand(
    val orderId: UUID,
    val customerId: UUID,
    val items: List<OrderItem>,
    val totalAmount: Double
  ) : UseCaseCommand

  val orderProcessingUseCase: UseCase<ProcessOrderCommand> = useCase {

    first(workflow = ValidateOrderWorkflow("validate-order"))

    // After validation, run inventory check and payment processing in parallel
    parallel {
      then(CheckInventoryWorkflow("check-inventory"))
      then(ProcessPaymentWorkflow("process-payment")) {
        ProcessPaymentCommand::orderId from ProcessOrderCommand::orderId  // This would be automatic, but including for clarity
        ProcessPaymentCommand::amount from ProcessOrderCommand::totalAmount // Map from command's totalAmount to amount in ProcessPaymentCommand
      }
    }

    thenIf(
      PrepareShipmentWorkflow("prepare-shipment"),
      predicate = { result ->
        val paymentSuccessful = when (result.getFromEvent(PaymentProcessedEvent::payment)) {
          is SuccessfulPayment -> true
          else -> false
        }
        result.context.getTypedData<Boolean>("inventoryAvailable") == true && paymentSuccessful
      }
    ) {
      // Map from different event fields to the shipment command fields
      PrepareShipmentCommand::items from InventoryVerifiedEvent::availableItems // from InventoryVerifiedEvent
    }
  }

  // -------------------------
  // Execute the use case
  // -------------------------
  val initialCommand = ProcessOrderCommand(orderId, customerId, items, totalAmount)

  when (val result = runBlocking { orderProcessingUseCase.execute(initialCommand) } ) {
    is Either.Right -> {
      println("Order processing completed successfully!")
      result.value.events.sortedBy { it.timestamp }.forEach { event ->
        println("Event: ${event::class.simpleName}")
      }
      // Access final events if needed
      result.value.events.filterIsInstance<ShipmentPreparedEvent>()
        .firstOrNull()?.let { shipmentEvent ->
          println("Shipment prepared with tracking number: ${shipmentEvent.trackingNumber}")
        }
    }

    is Either.Left -> {
      println("Error processing order: ${result.value}")
    }
  }
}


// -------------------------
// Commands
// -------------------------
data class ValidateOrderCommand(
  val orderId: UUID,
  val customerId: UUID,
  val items: List<OrderItem>,
  val totalAmount: Double
) : WorkflowCommand

data class CheckInventoryCommand(
  val orderId: UUID,
  val items: List<OrderItem>
) : WorkflowCommand

data class ProcessPaymentCommand(
  val orderId: UUID,
  val customerId: UUID,
  val amount: Double
) : WorkflowCommand

data class PrepareShipmentCommand(
  val orderId: UUID,
  val shippingAddress: String,
  val items: List<OrderItem>
) : WorkflowCommand

// -------------------------
// Domain Objects
// -------------------------
data class OrderItem(
  val productId: UUID,
  val quantity: Int,
  val price: Double
)

// -------------------------
// Events
// -------------------------
data class OrderValidatedEvent(
  override val id: UUID,
  override val timestamp: Instant,
  val orderId: UUID,
  val shippingAddress: String,
  val items: List<OrderItem>
) : Event

data class InventoryVerifiedEvent(
  override val id: UUID,
  override val timestamp: Instant,
  val orderId: UUID,
  val availableItems: List<OrderItem>
) : Event

sealed interface Payment
data class FailedPayment(val orderId: UUID, val amount: Double) : Payment
data class SuccessfulPayment(val orderId: UUID, val amount: Double, val transactionId: UUID) : Payment

data class PaymentProcessedEvent(
  override val id: UUID,
  override val timestamp: Instant,
  val payment: Payment
) : Event

data class ShipmentPreparedEvent(
  override val id: UUID,
  override val timestamp: Instant,
  val orderId: UUID,
  val trackingNumber: String
) : Event

// -------------------------
// Workflow Implementations
// -------------------------

class ValidateOrderWorkflow(override val id: String) : Workflow<ValidateOrderCommand, OrderValidatedEvent>() {
  override suspend fun executeWorkflow(
    input: ValidateOrderCommand
  ): Either<WorkflowError, WorkflowResult> = either {
    // Simulate order validation
    ensure(!input.items.isEmpty()) { WorkflowError.ValidationError("Order must contain at least one item") }

    val event = OrderValidatedEvent(
      id = UUID.randomUUID(),
      timestamp = Instant.now(),
      orderId = input.orderId,
      shippingAddress = "123 Main St", // Simplified for example
      items = input.items
    )
    WorkflowResult(listOf(event))
  }
}

class CheckInventoryWorkflow(override val id: String) : Workflow<CheckInventoryCommand, InventoryVerifiedEvent>() {
  override suspend fun executeWorkflow(
    input: CheckInventoryCommand
  ): Either<WorkflowError, WorkflowResult> = either {
    // Simulate inventory check
    delay(1000) // Simulate external service call

    val event = InventoryVerifiedEvent(
      id = UUID.randomUUID(),
      timestamp = Instant.now(),
      orderId = input.orderId,
      availableItems = input.items
    )

    WorkflowResult(
      listOf(event),
      WorkflowContext().addData("inventoryAvailable", true)
    )
  }
}

class ProcessPaymentWorkflow(override val id: String) : Workflow<ProcessPaymentCommand, PaymentProcessedEvent>() {
  override suspend fun executeWorkflow(
    input: ProcessPaymentCommand
  ): Either<WorkflowError, WorkflowResult> = either {
    // Simulate payment processing
    delay(1500) // Simulate external payment gateway call

    val event = PaymentProcessedEvent(
      id = UUID.randomUUID(),
      timestamp = Instant.now(),
      payment = SuccessfulPayment(
        orderId = input.orderId,
        transactionId = UUID.randomUUID(),
        amount = input.amount
      )
    )
    WorkflowResult(listOf(event))
  }
}

class PrepareShipmentWorkflow(override val id: String) : Workflow<PrepareShipmentCommand, ShipmentPreparedEvent>() {
  override suspend fun executeWorkflow(
    input: PrepareShipmentCommand
  ): Either<WorkflowError, WorkflowResult> = either {
    val event = ShipmentPreparedEvent(
      id = UUID.randomUUID(),
      timestamp = Instant.now(),
      orderId = input.orderId,
      trackingNumber = "TRACK-${UUID.randomUUID().toString().take(8)}"
    )
    WorkflowResult(listOf(event))
  }
}
