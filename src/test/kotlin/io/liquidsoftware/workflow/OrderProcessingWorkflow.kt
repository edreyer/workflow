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

  val orderProcessingUseCase: UseCase<ProcessOrderCommand> = useCase {
    startWith { command ->
      Either.Right(
        OrderDraftState(
          orderId = command.orderId,
          customerId = command.customerId,
          items = command.items,
          totalAmount = command.totalAmount
        )
      )
    }

    then(ValidateOrderWorkflow("validate-order"))

    parallelJoin(
      CalculateSubtotalWorkflow("calc-subtotal"),
      CalculateTaxWorkflow("calc-tax"),
    ) { subtotal, tax ->
      PricingState(
        orderId = subtotal.orderId,
        customerId = subtotal.customerId,
        items = subtotal.items,
        totalAmount = subtotal.totalAmount,
        shippingAddress = subtotal.shippingAddress,
        subtotal = subtotal.subtotal,
        tax = tax.tax,
        total = subtotal.subtotal + tax.tax
      )
    }

    then(EmitPricingWorkflow("emit-pricing"))

    // After validation, run inventory check and payment processing in parallel
    parallel {
      then(CheckInventoryWorkflow("check-inventory"))
      then(ProcessPaymentWorkflow("process-payment"))
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
    )
  }

  // -------------------------
  // Execute the use case
  // -------------------------
  val initialCommand = ProcessOrderCommand(orderId, customerId, items, totalAmount)

  when (val result = runBlocking { orderProcessingUseCase.execute(initialCommand) }) {
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
data class ProcessOrderCommand(
  val orderId: UUID,
  val customerId: UUID,
  val items: List<OrderItem>,
  val totalAmount: Double
) : UseCaseCommand

// -------------------------
// State
// -------------------------
data class OrderDraftState(
  val orderId: UUID,
  val customerId: UUID,
  val items: List<OrderItem>,
  val totalAmount: Double
) : WorkflowState

data class ValidatedOrderState(
  val orderId: UUID,
  val customerId: UUID,
  val items: List<OrderItem>,
  val totalAmount: Double,
  val shippingAddress: String
) : WorkflowState

data class SubtotalState(
  val orderId: UUID,
  val customerId: UUID,
  val items: List<OrderItem>,
  val totalAmount: Double,
  val shippingAddress: String,
  val subtotal: Double
) : WorkflowState

data class TaxState(
  val orderId: UUID,
  val customerId: UUID,
  val items: List<OrderItem>,
  val totalAmount: Double,
  val shippingAddress: String,
  val tax: Double
) : WorkflowState

data class PricingState(
  val orderId: UUID,
  val customerId: UUID,
  val items: List<OrderItem>,
  val totalAmount: Double,
  val shippingAddress: String,
  val subtotal: Double,
  val tax: Double,
  val total: Double
) : WorkflowState

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

data class SubtotalCalculatedEvent(
  override val id: UUID,
  override val timestamp: Instant,
  val orderId: UUID,
  val subtotal: Double
) : Event

data class TaxCalculatedEvent(
  override val id: UUID,
  override val timestamp: Instant,
  val orderId: UUID,
  val tax: Double
) : Event

data class PricingCalculatedEvent(
  override val id: UUID,
  override val timestamp: Instant,
  val orderId: UUID,
  val subtotal: Double,
  val tax: Double,
  val total: Double
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

class ValidateOrderWorkflow(override val id: String) : Workflow<OrderDraftState, ValidatedOrderState>() {
  override suspend fun executeWorkflow(
    input: OrderDraftState
  ): Either<WorkflowError, WorkflowResult<ValidatedOrderState>> = either {
    // Simulate order validation
    ensure(input.items.isNotEmpty()) { WorkflowError.ValidationError("Order must contain at least one item") }

    val event = OrderValidatedEvent(
      id = UUID.randomUUID(),
      timestamp = Instant.now(),
      orderId = input.orderId,
      shippingAddress = "123 Main St", // Simplified for example
      items = input.items
    )
    WorkflowResult(
      ValidatedOrderState(
        orderId = input.orderId,
        customerId = input.customerId,
        items = input.items,
        totalAmount = input.totalAmount,
        shippingAddress = event.shippingAddress
      ),
      listOf(event)
    )
  }
}

class CalculateSubtotalWorkflow(override val id: String) : Workflow<ValidatedOrderState, SubtotalState>() {
  override suspend fun executeWorkflow(
    input: ValidatedOrderState
  ): Either<WorkflowError, WorkflowResult<SubtotalState>> = either {
    val subtotal = input.items.sumOf { it.quantity * it.price }
    val event = SubtotalCalculatedEvent(
      id = UUID.randomUUID(),
      timestamp = Instant.now(),
      orderId = input.orderId,
      subtotal = subtotal
    )
    WorkflowResult(
      SubtotalState(
        orderId = input.orderId,
        customerId = input.customerId,
        items = input.items,
        totalAmount = input.totalAmount,
        shippingAddress = input.shippingAddress,
        subtotal = subtotal
      ),
      listOf(event)
    )
  }
}

class CalculateTaxWorkflow(override val id: String) : Workflow<ValidatedOrderState, TaxState>() {
  override suspend fun executeWorkflow(
    input: ValidatedOrderState
  ): Either<WorkflowError, WorkflowResult<TaxState>> = either {
    val tax = input.totalAmount * 0.08
    val event = TaxCalculatedEvent(
      id = UUID.randomUUID(),
      timestamp = Instant.now(),
      orderId = input.orderId,
      tax = tax
    )
    WorkflowResult(
      TaxState(
        orderId = input.orderId,
        customerId = input.customerId,
        items = input.items,
        totalAmount = input.totalAmount,
        shippingAddress = input.shippingAddress,
        tax = tax
      ),
      listOf(event)
    )
  }
}

class EmitPricingWorkflow(override val id: String) : Workflow<PricingState, PricingState>() {
  override suspend fun executeWorkflow(
    input: PricingState
  ): Either<WorkflowError, WorkflowResult<PricingState>> = either {
    val event = PricingCalculatedEvent(
      id = UUID.randomUUID(),
      timestamp = Instant.now(),
      orderId = input.orderId,
      subtotal = input.subtotal,
      tax = input.tax,
      total = input.total
    )
    WorkflowResult(input, listOf(event))
  }
}

class CheckInventoryWorkflow(override val id: String) : Workflow<PricingState, PricingState>() {
  override suspend fun executeWorkflow(
    input: PricingState
  ): Either<WorkflowError, WorkflowResult<PricingState>> = either {
    // Simulate inventory check
    delay(1000) // Simulate external service call

    val event = InventoryVerifiedEvent(
      id = UUID.randomUUID(),
      timestamp = Instant.now(),
      orderId = input.orderId,
      availableItems = input.items
    )

    WorkflowResult(
      input,
      listOf(event),
      WorkflowContext().addData("inventoryAvailable", true)
    )
  }
}

class ProcessPaymentWorkflow(override val id: String) : Workflow<PricingState, PricingState>() {
  override suspend fun executeWorkflow(
    input: PricingState
  ): Either<WorkflowError, WorkflowResult<PricingState>> = either {
    // Simulate payment processing
    delay(1500) // Simulate external payment gateway call

    val event = PaymentProcessedEvent(
      id = UUID.randomUUID(),
      timestamp = Instant.now(),
      payment = SuccessfulPayment(
        orderId = input.orderId,
        transactionId = UUID.randomUUID(),
        amount = input.total
      )
    )
    WorkflowResult(input, listOf(event))
  }
}

class PrepareShipmentWorkflow(override val id: String) : Workflow<PricingState, PricingState>() {
  override suspend fun executeWorkflow(
    input: PricingState
  ): Either<WorkflowError, WorkflowResult<PricingState>> = either {
    val event = ShipmentPreparedEvent(
      id = UUID.randomUUID(),
      timestamp = Instant.now(),
      orderId = input.orderId,
      trackingNumber = "TRACK-${UUID.randomUUID().toString().take(8)}"
    )
    WorkflowResult(input, listOf(event))
  }
}
