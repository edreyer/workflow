package io.liquidsoftware.workflow.tutorial.ch04

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.context.ensure
import arrow.core.raise.context.ensureNotNull
import io.liquidsoftware.workflow.Failure
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.Workflow
import io.liquidsoftware.workflow.mapError
import io.liquidsoftware.workflow.invoke
import io.liquidsoftware.workflow.useCase
import io.liquidsoftware.workflow.workflow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Tutorial chapter 4: docs/tutorial/04-reusable-steps.md

data class PlaceOrder(val customerId: String, val items: List<LineItem>, val cardToken: String)
data class LineItem(val sku: String, val quantity: Int)
data class Cart(val customerId: String, val items: List<LineItem>, val cardToken: String)
data class PricedCart(val cart: Cart, val total: Long)
data class PaidOrder(val cart: Cart, val total: Long, val chargeId: String)
data class OrderConfirmation(val customerId: String, val total: Long, val chargeId: String)

sealed interface OrderError {
  data class UnknownProduct(val sku: String) : OrderError
  data class PaymentFailed(val reason: String) : OrderError
}

// The payment module has its own error type. It knows nothing about orders.
sealed interface PaymentError {
  data object Declined : PaymentError
  data object InsufficientFunds : PaymentError
}

class Catalog(private val prices: Map<String, Long>) {
  context(_: Raise<OrderError>)
  fun price(cart: Cart): PricedCart =
    PricedCart(cart, cart.items.sumOf { ensureNotNull(prices[it.sku]) { OrderError.UnknownProduct(it.sku) } * it.quantity })
}

class PaymentGateway {
  context(_: Raise<PaymentError>)
  suspend fun charge(cardToken: String, amount: Long): String {
    ensure(cardToken != "declined") { PaymentError.Declined }
    ensure(amount <= 10_000) { PaymentError.InsufficientFunds }
    return "ch-$cardToken-$amount"
  }
}

// Reusable steps: named once, with their input, output and error types.
fun priceCart(catalog: Catalog): Workflow<Cart, PricedCart, OrderError> =
  workflow("price-cart") { cart -> catalog.price(cart) }

// A step that never fails declares Nothing, and fits any pipeline.
val bulkDiscount: Workflow<PricedCart, PricedCart, Nothing> =
  workflow("bulk-discount") { priced ->
    if (priced.cart.items.sumOf { it.quantity } >= 10) priced.copy(total = priced.total * 9 / 10) else priced
  }

fun chargeCard(gateway: PaymentGateway): Workflow<PricedCart, PaidOrder, PaymentError> =
  workflow("charge-card") { priced ->
    PaidOrder(priced.cart, priced.total, gateway.charge(priced.cart.cardToken, priced.total))
  }

fun placeOrder(catalog: Catalog, gateway: PaymentGateway): UseCase<PlaceOrder, Failure<OrderError>, OrderConfirmation> =
  useCase("PlaceOrder") {
    startWith { command -> Cart(command.customerId, command.items, command.cardToken) }
      .then(priceCart(catalog))
      .then(bulkDiscount)
      // A PaymentError step doesn't fit an OrderError pipeline until it is adapted.
      .then(chargeCard(gateway).mapError { error -> OrderError.PaymentFailed(error.toString()) })
      .result { paid -> OrderConfirmation(paid.cart.customerId, paid.total, paid.chargeId) }
  }

class Ch04ReusableStepsTest {
  private val catalog = Catalog(mapOf("book" to 1_500L, "pen" to 250L))
  private val gateway = PaymentGateway()

  @Test
  fun `a reusable step runs on its own in a test`() = runTest {
    val cart = Cart("c-1", listOf(LineItem("pen", 10)), "tok")

    val result = bulkDiscount(PricedCart(cart, 2_500))

    assertEquals(Either.Right(PricedCart(cart, 2_250)), result.map { it.state })
    assertEquals(Either.Left(Failure.Raised(OrderError.UnknownProduct("mug"))), priceCart(catalog)(cart.copy(items = listOf(LineItem("mug", 1)))))
  }

  @Test
  fun `mapError adapts a step written for another error type`() = runTest {
    val useCase = placeOrder(catalog, gateway)

    assertEquals(
      Either.Right(OrderConfirmation("c-1", 3_000, "ch-tok-3000")),
      useCase(PlaceOrder("c-1", listOf(LineItem("book", 2)), "tok")),
    )
    assertEquals(
      Either.Left(Failure.Raised(OrderError.PaymentFailed("Declined"))),
      useCase(PlaceOrder("c-1", listOf(LineItem("book", 2)), "declined")),
    )
  }
}
