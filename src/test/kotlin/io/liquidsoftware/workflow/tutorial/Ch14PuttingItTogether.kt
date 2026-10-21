package io.liquidsoftware.workflow.tutorial.ch14

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.context.ensure
import arrow.core.raise.context.ensureNotNull
import arrow.core.raise.ensure
import arrow.resilience.Schedule
import io.liquidsoftware.workflow.BaseEvent
import io.liquidsoftware.workflow.Failure
import io.liquidsoftware.workflow.Pipeline
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.UseCaseConfig
import io.liquidsoftware.workflow.UseCaseSummary
import io.liquidsoftware.workflow.Workflow
import io.liquidsoftware.workflow.WorkflowContext
import io.liquidsoftware.workflow.mapError
import io.liquidsoftware.workflow.mapInput
import io.liquidsoftware.workflow.pipeline
import io.liquidsoftware.workflow.retry
import io.liquidsoftware.workflow.timeout
import io.liquidsoftware.workflow.useCase
import io.liquidsoftware.workflow.useCaseConfig
import io.liquidsoftware.workflow.workflow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Tutorial chapter 14: docs/tutorial/14-putting-it-together.md

// ---- Public input and output ----------------------------------------------------------------

// The caller picks the order id, so a retried request can be recognised.

data class PlaceOrder(val orderId: String, val customerId: String, val items: List<LineItem>, val cardToken: String, val express: Boolean = false)
data class LineItem(val sku: String, val quantity: Int)
data class OrderDto(val orderId: String, val total: Long, val trackingNumber: String)

// ---- State ----------------------------------------------------------------------------------

data class Cart(val orderId: String, val customerId: String, val items: List<LineItem>, val cardToken: String, val express: Boolean)
data class Customer(val id: String, val email: String, val member: Boolean)
data class PricedOrder(val cart: Cart, val customer: Customer, val total: Long)
data class PaidOrder(val order: PricedOrder, val chargeId: String)
data class Shipment(val paid: PaidOrder, val trackingNumber: String)

// ---- Events ---------------------------------------------------------------------------------

data class OrderPaid(val customerId: String, val chargeId: String) : BaseEvent()
data class OrderShipped(val trackingNumber: String) : BaseEvent()

// ---- Errors ---------------------------------------------------------------------------------

sealed interface OrderError {
  data object EmptyCart : OrderError
  data class UnknownProduct(val sku: String) : OrderError
  data object UnknownCustomer : OrderError
  data class PaymentFailed(val error: PaymentError) : OrderError
}

sealed interface PaymentError {
  data object Declined : PaymentError
  data object Unavailable : PaymentError
  data object TimedOut : PaymentError
}

data class ApiError(val status: Int, val code: String)

fun toApiError(failure: Failure<OrderError>): ApiError = when (failure) {
  is Failure.Raised -> when (val error = failure.error) {
    OrderError.EmptyCart -> ApiError(400, "empty-cart")
    is OrderError.UnknownProduct -> ApiError(404, "unknown-product")
    OrderError.UnknownCustomer -> ApiError(404, "unknown-customer")
    is OrderError.PaymentFailed -> when (error.error) {
      PaymentError.Declined -> ApiError(402, "payment-declined")
      PaymentError.Unavailable, PaymentError.TimedOut -> ApiError(503, "payment-unavailable")
    }
  }
  is Failure.Unexpected -> ApiError(500, "internal")
}

// ---- Domain services: plain Kotlin and Arrow ------------------------------------------------

class Catalog(private val prices: Map<String, Long>) {
  context(_: Raise<OrderError>)
  fun total(items: List<LineItem>): Long =
    items.sumOf { ensureNotNull(prices[it.sku]) { OrderError.UnknownProduct(it.sku) } * it.quantity }
}

class Customers(private val known: Map<String, Customer>) {
  context(_: Raise<OrderError>)
  fun find(id: String): Customer = ensureNotNull(known[id]) { OrderError.UnknownCustomer }
}

class PaymentGateway(private var unavailableFor: Int = 0) {
  context(_: Raise<PaymentError>)
  suspend fun charge(cardToken: String, amount: Long): String {
    ensure(unavailableFor-- <= 0) { PaymentError.Unavailable }
    ensure(cardToken != "declined") { PaymentError.Declined }
    return "ch-$amount"
  }
}

class Mailer {
  val sent = mutableListOf<String>()
  suspend fun send(to: String, subject: String) { sent += "$to: $subject" }
}

// ---- Reusable steps -------------------------------------------------------------------------

fun loadCustomer(customers: Customers): Workflow<Cart, Customer, OrderError> =
  workflow("load-customer") { cart -> customers.find(cart.customerId) }

fun priceItems(catalog: Catalog): Workflow<Cart, Long, OrderError> =
  workflow("price-items") { cart -> catalog.total(cart.items) }

val memberDiscount: Workflow<PricedOrder, PricedOrder, Nothing> =
  workflow("member-discount") { order -> order.copy(total = order.total - order.total / 10) }

private val transient = Schedule.exponential<Failure<PaymentError>>(100.milliseconds)
  .and(Schedule.recurs(2))
  .doWhile { failure, _ -> failure != Failure.Raised(PaymentError.Declined) }

fun chargeCard(gateway: PaymentGateway): Workflow<PricedOrder, PaidOrder, OrderError> =
  workflow<PricedOrder, PaidOrder, PaymentError>("charge-card") { order ->
    val chargeId = gateway.charge(order.cart.cardToken, order.total)
    emit(OrderPaid(order.customer.id, chargeId))
    PaidOrder(order, chargeId)
  }
    .timeout(2.seconds) { PaymentError.TimedOut }
    .retry(transient)
    .mapError { OrderError.PaymentFailed(it) }

val expressShipping: Workflow<PaidOrder, Shipment, Nothing> =
  workflow("express-shipping") { paid -> Shipment(paid, "EXP-${paid.chargeId}").also { emit(OrderShipped(it.trackingNumber)) } }

val standardShipping: Workflow<PaidOrder, Shipment, Nothing> =
  workflow("standard-shipping") { paid -> Shipment(paid, "STD-${paid.chargeId}").also { emit(OrderShipped(it.trackingNumber)) } }

fun sendReceipt(mailer: Mailer): Workflow<Customer, Unit, Nothing> =
  workflow("send-receipt") { customer -> mailer.send(customer.email, "Your order is on its way") }

// A named section: everything after payment.
fun fulfil(mailer: Mailer): Pipeline<PaidOrder, OrderError, Shipment> =
  pipeline<PaidOrder, OrderError>()
    .branch(ifTrue = expressShipping, ifFalse = standardShipping) { paid -> paid.order.cart.express }
    .thenLaunch(sendReceipt(mailer).mapInput { shipment: Shipment -> shipment.paid.order.customer }, timeout = 5.seconds)

// ---- The use case ---------------------------------------------------------------------------

class Services(
  val catalog: Catalog,
  val customers: Customers,
  val gateway: PaymentGateway,
  val mailer: Mailer,
)

fun placeOrder(services: Services, config: UseCaseConfig): UseCase<PlaceOrder, ApiError, OrderDto> =
  useCase("PlaceOrder", errors = ::toApiError, config = config) {
    startWith { command ->
      ensure(command.items.isNotEmpty()) { OrderError.EmptyCart }
      Cart(command.orderId, command.customerId, command.items, command.cardToken, command.express)
    }
      .parallelJoin(loadCustomer(services.customers), priceItems(services.catalog)) { customer, total ->
        PricedOrder(this, customer, total)
      }
      .thenIf(memberDiscount) { order -> order.customer.member }
      .then(chargeCard(services.gateway))
      .then(fulfil(services.mailer))
      .result { shipment -> OrderDto(shipment.paid.order.cart.orderId, shipment.paid.order.total, shipment.trackingNumber) }
      .logging {
        success { (shipment) -> field("express", shipment.paid.order.cart.express) }
      }
  }

// For a dependency injection container that wants one type per use case.
class PlaceOrderUseCase(services: Services, config: UseCaseConfig) :
  UseCase<PlaceOrder, ApiError, OrderDto> by placeOrder(services, config)

// A caller depends on the UseCase type, not on how it is built.
class OrderController(private val placeOrder: UseCase<PlaceOrder, ApiError, OrderDto>) {
  suspend fun post(command: PlaceOrder, requestId: String): Pair<Int, Any> =
    placeOrder(command, WorkflowContext.of(WorkflowContext.CORRELATION_ID to requestId)).fold(
      ifLeft = { error -> error.status to error.code },
      ifRight = { dto -> 201 to dto },
    )
}

class Ch14PuttingItTogetherTest {
  private val alice = Customer("alice", "alice@example.com", member = true)
  private val bob = Customer("bob", "bob@example.com", member = false)
  private val summaries = mutableListOf<UseCaseSummary>()
  private val config = useCaseConfig { onSummary { summaries += it } }

  private fun services(gateway: PaymentGateway = PaymentGateway()) = Services(
    Catalog(mapOf("book" to 1_500L, "pen" to 250L)),
    Customers(mapOf("alice" to alice, "bob" to bob)),
    gateway,
    Mailer(),
  )

  @Test
  fun `places an order end to end`() = runTest {
    val services = services(PaymentGateway(unavailableFor = 1))
    val useCase = PlaceOrderUseCase(services, config)

    val result = useCase(PlaceOrder("o-1", "alice", listOf(LineItem("book", 2)), "tok", express = true))

    assertEquals(Either.Right(OrderDto("o-1", 2_700, "EXP-ch-2700")), result)
    assertEquals(listOf("alice@example.com: Your order is on its way"), services.mailer.sent)
    assertEquals(
      setOf(
        "load-customer", "price-items", "parallelJoin(load-customer,price-items)",
        "member-discount", "charge-card", "express-shipping", "send-receipt",
      ),
      summaries.single().executions.map { it.workflowId }.toSet(),
    )
  }

  @Test
  fun `maps every failure to an API error`() = runTest {
    val useCase = placeOrder(services(), config)

    assertEquals(Either.Left(ApiError(400, "empty-cart")), useCase(PlaceOrder("o-1", "bob", emptyList(), "tok")))
    assertEquals(Either.Left(ApiError(404, "unknown-customer")), useCase(PlaceOrder("o-1", "eve", listOf(LineItem("pen", 1)), "tok")))
    assertEquals(Either.Left(ApiError(402, "payment-declined")), useCase(PlaceOrder("o-1", "bob", listOf(LineItem("pen", 1)), "declined")))
    assertEquals(
      Either.Left(ApiError(503, "payment-unavailable")),
      placeOrder(services(PaymentGateway(unavailableFor = 5)), config)(PlaceOrder("o-1", "bob", listOf(LineItem("pen", 1)), "tok")),
    )
  }

  @Test
  fun `callers test against a fake use case`() = runTest {
    val controller = OrderController(UseCase { _, _ -> Either.Left(ApiError(503, "payment-unavailable")) })

    assertEquals(503 to "payment-unavailable", controller.post(PlaceOrder("o-1", "bob", emptyList(), "tok"), "req-1"))
  }
}
