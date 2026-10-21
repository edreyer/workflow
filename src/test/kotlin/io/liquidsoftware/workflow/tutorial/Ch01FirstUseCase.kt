package io.liquidsoftware.workflow.tutorial.ch01

import arrow.core.Either
import io.liquidsoftware.workflow.Failure
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.useCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Tutorial chapter 1: docs/tutorial/01-first-use-case.md

// Input: what the caller sends.
data class PlaceOrder(val customerId: String, val items: List<LineItem>)
data class LineItem(val sku: String, val quantity: Int)

// State: what the steps pass to each other.
data class Cart(val customerId: String, val items: List<LineItem>)
data class PricedCart(val cart: Cart, val total: Long)

// Output: what the caller gets back.
data class OrderConfirmation(val customerId: String, val total: Long)

val prices = mapOf("book" to 1_500L, "pen" to 250L)

val placeOrder: UseCase<PlaceOrder, Failure<Nothing>, OrderConfirmation> =
  useCase("PlaceOrder") {
    startWith { command -> Cart(command.customerId, command.items) }
      .then("price-cart") { cart ->
        PricedCart(cart, cart.items.sumOf { prices.getValue(it.sku) * it.quantity })
      }
      .result { priced -> OrderConfirmation(priced.cart.customerId, priced.total) }
  }

class Ch01FirstUseCaseTest {
  @Test
  fun `places an order`() = runTest {
    val result = placeOrder(PlaceOrder("c-1", listOf(LineItem("book", 2), LineItem("pen", 1))))

    assertEquals(Either.Right(OrderConfirmation("c-1", 3_250)), result)
  }

  @Test
  fun `callers handle the Either`() = runTest {
    val message = placeOrder(PlaceOrder("c-1", listOf(LineItem("pen", 4)))).fold(
      ifLeft = { failure -> "failed: $failure" },
      ifRight = { confirmation -> "charged ${confirmation.total}" },
    )

    assertEquals("charged 1000", message)
  }
}
