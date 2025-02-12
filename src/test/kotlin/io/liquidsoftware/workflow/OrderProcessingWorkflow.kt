package io.liquidsoftware.workflow

import arrow.core.Either
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID

// Commands
data class ValidateOrderCommand(
    val orderId: UUID,
    val customerId: UUID,
    val items: List<OrderItem>,
    val totalAmount: Double
) : Command

data class CheckInventoryCommand(
    val orderId: UUID,
    val items: List<OrderItem>
) : Command

data class ProcessPaymentCommand(
    val orderId: UUID,
    val customerId: UUID,
    val amount: Double
) : Command

data class PrepareShipmentCommand(
    val orderId: UUID,
    val shippingAddress: String,
    val items: List<OrderItem>
) : Command

// Domain Objects
data class OrderItem(
    val productId: UUID,
    val quantity: Int,
    val price: Double
)

// Events
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

data class PaymentProcessedEvent(
    override val id: UUID,
    override val timestamp: Instant,
    val orderId: UUID,
    val transactionId: UUID,
    val amount: Double
) : Event

data class ShipmentPreparedEvent(
    override val id: UUID,
    override val timestamp: Instant,
    val orderId: UUID,
    val trackingNumber: String
) : Event

// Workflow Implementations
class ValidateOrderWorkflow(override val id: String) : Workflow<ValidateOrderCommand, OrderValidatedEvent>() {
    override suspend fun executeWorkflow(
        input: ValidateOrderCommand
    ): Either<WorkflowError, WorkflowResult> {
        // Simulate order validation
        if (input.items.isEmpty()) {
            return Either.Left(WorkflowError.ValidationError("Order must contain at least one item"))
        }

        val event = OrderValidatedEvent(
            id = UUID.randomUUID(),
            timestamp = Instant.now(),
            orderId = input.orderId,
            shippingAddress = "123 Main St", // Simplified for example
            items = input.items
        )
        return Either.Right(WorkflowResult(listOf(event)))
    }
}

class CheckInventoryWorkflow(override val id: String) : Workflow<CheckInventoryCommand, InventoryVerifiedEvent>() {
    override suspend fun executeWorkflow(
        input: CheckInventoryCommand
    ): Either<WorkflowError, WorkflowResult> {
        // Simulate inventory check
        delay(1000) // Simulate external service call

        val event = InventoryVerifiedEvent(
            id = UUID.randomUUID(),
            timestamp = Instant.now(),
            orderId = input.orderId,
            availableItems = input.items
        )


        return Either.Right(WorkflowResult(
          listOf(event),
          WorkflowContext().addData("inventoryAvailable", true),),
        )
    }
}

class ProcessPaymentWorkflow(override val id: String) : Workflow<ProcessPaymentCommand, PaymentProcessedEvent>() {
    override suspend fun executeWorkflow(
        input: ProcessPaymentCommand
    ): Either<WorkflowError, WorkflowResult> {
        // Simulate payment processing
        delay(1500) // Simulate external payment gateway call

        val event = PaymentProcessedEvent(
            id = UUID.randomUUID(),
            timestamp = Instant.now(),
            orderId = input.orderId,
            transactionId = UUID.randomUUID(),
            amount = input.amount
        )
        return Either.Right(WorkflowResult(listOf(event)))
    }
}

class PrepareShipmentWorkflow(override val id: String) : Workflow<PrepareShipmentCommand, ShipmentPreparedEvent>() {
  override suspend fun executeWorkflow(
    input: PrepareShipmentCommand
  ): Either<WorkflowError, WorkflowResult> {
    val event = ShipmentPreparedEvent(
      id = UUID.randomUUID(),
      timestamp = Instant.now(),
      orderId = input.orderId,
      trackingNumber = "TRACK-${UUID.randomUUID().toString().take(8)}"
    )
    return Either.Right(WorkflowResult(listOf(event)))
  }
}

// Example Usage
fun main() {
  val orderId = UUID.randomUUID()
  val customerId = UUID.randomUUID()
  val items = listOf(
    OrderItem(UUID.randomUUID(), 2, 29.99),
    OrderItem(UUID.randomUUID(), 1, 49.99)
  )
  val totalAmount = items.sumOf { it.quantity * it.price }

  val orderProcessingUseCase = useCase(
    initialWorkflow = ValidateOrderWorkflow("validate-order")
  ) {
    // After validation, run inventory check and payment processing in parallel
    runParallel(true)

    then(CheckInventoryWorkflow("check-inventory")) { result ->
      // Access the validated event from the previous workflow's result
      val validatedEvent = result.events.filterIsInstance<OrderValidatedEvent>().first()
      CheckInventoryCommand(validatedEvent.orderId, validatedEvent.items)
    }

    then(ProcessPaymentWorkflow("process-payment")) { result ->
      // Access the validated event from the previous workflow's result
      val validatedEvent = result.events.filterIsInstance<OrderValidatedEvent>().first()
      ProcessPaymentCommand(validatedEvent.orderId, customerId, totalAmount)
    }

    // Switch back to sequential for shipment preparation
    runParallel(false)

    thenIf(PrepareShipmentWorkflow("prepare-shipment"),
      predicate = { result ->
        // Check previous events to determine if we should proceed
        result.context.getData("inventoryAvailable") == true &&
        result.events.any { it is PaymentProcessedEvent }
      }
    ) { result ->
      // Transform previous events into the shipment command
      val validatedEvent = result.events.filterIsInstance<OrderValidatedEvent>().first()
      val inventoryEvent = result.events.filterIsInstance<InventoryVerifiedEvent>().first()

      PrepareShipmentCommand(
        orderId = validatedEvent.orderId,
        shippingAddress = validatedEvent.shippingAddress,
        items = inventoryEvent.availableItems // Use verified inventory items
      )
    }
  }

  // Execute the use case
  val initialCommand = ValidateOrderCommand(orderId, customerId, items, totalAmount)
  when (val result = runBlocking { orderProcessingUseCase.execute(initialCommand) }) {
    is Either.Right -> {
      println("Order processing completed successfully!")
      result.value.events.forEach { event ->
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
