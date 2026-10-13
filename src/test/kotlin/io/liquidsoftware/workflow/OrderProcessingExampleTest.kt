package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.context.ensure
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The order-processing example from the docs, kept compiling and passing.
 */
class OrderProcessingExampleTest {

  // Input and output
  data class ProcessOrder(val orderId: UUID, val items: List<OrderItem>, val paymentToken: String)
  data class OrderReceipt(val orderId: UUID, val total: Double, val trackingNumber: String?)
  data class OrderItem(val sku: String, val quantity: Int, val price: Double)

  // State
  data class OrderDraft(val orderId: UUID, val items: List<OrderItem>, val paymentToken: String)
  data class ValidatedOrder(val orderId: UUID, val items: List<OrderItem>, val paymentToken: String)
  data class Subtotal(val amount: Double)
  data class Tax(val amount: Double)
  data class PricedOrder(
    val order: ValidatedOrder,
    val subtotal: Double,
    val tax: Double,
    val inventoryAvailable: Boolean = false,
  ) {
    val total: Double get() = subtotal + tax
  }

  // Events
  data class OrderValidated(val orderId: UUID) : BaseEvent()
  data class PaymentCaptured(val orderId: UUID, val amount: Double) : BaseEvent()
  data class ShipmentPrepared(val orderId: UUID, val trackingNumber: String) : BaseEvent()
  data class ConfirmationSent(val orderId: UUID) : BaseEvent()

  // Errors
  sealed interface OrderError {
    data object EmptyOrder : OrderError
    data class PaymentDeclined(val reason: String) : OrderError
  }

  data class ApiError(val status: Int, val code: String)

  // Steps
  private val validateOrder = workflow<OrderDraft, ValidatedOrder, OrderError>("validate-order") { draft ->
    ensure(draft.items.isNotEmpty()) { OrderError.EmptyOrder }
    emit(OrderValidated(draft.orderId))
    ValidatedOrder(draft.orderId, draft.items, draft.paymentToken)
  }

  private val calculateSubtotal = workflow<ValidatedOrder, Subtotal, Nothing>("calculate-subtotal") { order ->
    Subtotal(order.items.sumOf { it.quantity * it.price })
  }

  private val calculateTax = workflow<ValidatedOrder, Tax, Nothing>("calculate-tax") { order ->
    Tax(order.items.sumOf { it.quantity * it.price } * 0.08)
  }

  // Domain services: plain Kotlin and Arrow, with no dependency on this library.
  class InventoryService {
    suspend fun isAvailable(order: PricedOrder): Boolean {
      delay(100) // inventory service call
      return order.order.items.isNotEmpty()
    }
  }

  class PaymentService {
    context(_: Raise<OrderError>)
    suspend fun capture(order: PricedOrder): Double {
      delay(150) // payment gateway call
      ensure(order.order.paymentToken != "declined") { OrderError.PaymentDeclined("card declined") }
      return order.total
    }
  }

  private val inventory = InventoryService()
  private val payments = PaymentService()

  private val checkInventory = workflow<PricedOrder, PricedOrder, Nothing>("check-inventory") { order ->
    order.copy(inventoryAvailable = inventory.isAvailable(order))
  }

  private val capturePayment = workflow<PricedOrder, Unit, OrderError>("capture-payment") { order ->
    emit(PaymentCaptured(order.order.orderId, payments.capture(order)))
  }

  private val notifyWarehouse = workflow<PricedOrder, Unit, Nothing>("notify-warehouse") { order ->
    delay(50) // warehouse service call
  }

  private val prepareShipment = workflow<PricedOrder, PricedOrder, Nothing>("prepare-shipment") { order ->
    emit(ShipmentPrepared(order.order.orderId, "TRACK-${order.order.orderId.toString().take(8)}"))
    order
  }

  private val sendConfirmation = workflow<PricedOrder, Unit, Nothing>("send-confirmation") { order ->
    emit(ConfirmationSent(order.order.orderId))
  }

  private fun toApiError(failure: Failure<OrderError>): ApiError =
    when (failure) {
      is Failure.Raised -> when (failure.error) {
        OrderError.EmptyOrder -> ApiError(400, "empty-order")
        is OrderError.PaymentDeclined -> ApiError(402, "payment-declined")
      }
      is Failure.Unexpected -> ApiError(500, "internal")
    }

  // Use case
  private val processOrder: UseCase<ProcessOrder, ApiError, OrderReceipt> =
    useCase(name = "ProcessOrder", errors = ::toApiError) {
      startWith { command -> OrderDraft(command.orderId, command.items, command.paymentToken) }
        .then(validateOrder)
        .parallelJoin(calculateSubtotal, calculateTax) { subtotal, tax ->
          PricedOrder(order = this, subtotal = subtotal.amount, tax = tax.amount) // `this` is the ValidatedOrder
        }
        .then(checkInventory)
        .parallel(capturePayment, notifyWarehouse)
        .thenIf(prepareShipment) { order -> order.inventoryAvailable }
        .thenLaunch(sendConfirmation)
        .result { order ->
          OrderReceipt(order.order.orderId, order.total, events.lastEvent<ShipmentPrepared>()?.trackingNumber)
        }
        .logging {
          success { (order) -> field("itemCount", order.order.items.size) }
          failure { failure -> field("unexpected", failure is Failure.Unexpected) }
        }
    }

  private val items = listOf(OrderItem("sku-1", 2, 29.99), OrderItem("sku-2", 1, 49.99))

  @Test
  fun `processes an order end to end`() = runTest {
    val orderId = UUID.randomUUID()

    val receipt = assertIs<Either.Right<OrderReceipt>>(processOrder(ProcessOrder(orderId, items, "tok"))).value

    assertTrue(receipt.trackingNumber!!.startsWith("TRACK-"))
  }

  @Test
  fun `maps application errors to the API error`() = runTest {
    assertEquals(Either.Left(ApiError(400, "empty-order")), processOrder(ProcessOrder(UUID.randomUUID(), emptyList(), "tok")))
    assertEquals(Either.Left(ApiError(402, "payment-declined")), processOrder(ProcessOrder(UUID.randomUUID(), items, "declined")))
  }
}
