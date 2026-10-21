package io.liquidsoftware.workflow.tutorial.ch03

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.context.ensureNotNull
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.liquidsoftware.workflow.Failure
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.useCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Tutorial chapter 3: docs/tutorial/03-domain-functions.md

data class PlaceOrder(val customerId: String, val items: List<LineItem>)
data class LineItem(val sku: String, val quantity: Int)
data class Cart(val customerId: String, val items: List<LineItem>)
data class PricedCart(val cart: Cart, val total: Long)
data class OrderConfirmation(val customerId: String, val total: Long)

sealed interface OrderError {
  data object EmptyCart : OrderError
  data class UnknownProduct(val sku: String) : OrderError
  data object CustomerBlocked : OrderError
}

data class ApiError(val status: Int, val code: String)

fun toApiError(failure: Failure<OrderError>): ApiError =
  when (failure) {
    is Failure.Raised -> when (val error = failure.error) {
      OrderError.EmptyCart -> ApiError(400, "empty-cart")
      is OrderError.UnknownProduct -> ApiError(404, "unknown-product:${error.sku}")
      OrderError.CustomerBlocked -> ApiError(403, "customer-blocked")
    }
    is Failure.Unexpected -> ApiError(500, "internal")
  }

// Domain code: plain Kotlin and Arrow. Nothing here depends on the workflow library.
class Catalog(private val prices: Map<String, Long>) {
  context(_: Raise<OrderError>)
  fun price(cart: Cart): PricedCart {
    val total = cart.items.sumOf { item ->
      val unitPrice = ensureNotNull(prices[item.sku]) { OrderError.UnknownProduct(item.sku) }
      unitPrice * item.quantity
    }
    return PricedCart(cart, total)
  }
}

class Customers(private val blocked: Set<String>) {
  fun isBlocked(customerId: String): Boolean = customerId in blocked
}

// The use case wires domain functions into named steps. Dependencies are plain parameters.
fun placeOrder(catalog: Catalog, customers: Customers): UseCase<PlaceOrder, ApiError, OrderConfirmation> =
  useCase("PlaceOrder", errors = ::toApiError) {
    startWith { command ->
      ensure(command.items.isNotEmpty()) { OrderError.EmptyCart }
      Cart(command.customerId, command.items)
    }
      .guard("check-customer") { cart ->
        ensure(!customers.isBlocked(cart.customerId)) { OrderError.CustomerBlocked }
      }
      .then("price-cart") { cart -> catalog.price(cart) }
      .result { priced -> OrderConfirmation(priced.cart.customerId, priced.total) }
  }

class Ch03DomainFunctionsTest {
  private val catalog = Catalog(mapOf("book" to 1_500L, "pen" to 250L))
  private val customers = Customers(blocked = setOf("c-blocked"))
  private val useCase = placeOrder(catalog, customers)

  @Test
  fun `domain functions are tested without the library`() {
    val cart = Cart("c-1", listOf(LineItem("mug", 1)))

    assertEquals(Either.Left(OrderError.UnknownProduct("mug")), either<OrderError, PricedCart> { catalog.price(cart) })
  }

  @Test
  fun `an unknown product is now a typed error`() = runTest {
    assertEquals(
      Either.Left(ApiError(404, "unknown-product:mug")),
      useCase(PlaceOrder("c-1", listOf(LineItem("mug", 1)))),
    )
  }

  @Test
  fun `guard stops the pipeline and passes the state on otherwise`() = runTest {
    val items = listOf(LineItem("book", 1))

    assertEquals(Either.Left(ApiError(403, "customer-blocked")), useCase(PlaceOrder("c-blocked", items)))
    assertEquals(Either.Right(OrderConfirmation("c-1", 1_500)), useCase(PlaceOrder("c-1", items)))
  }
}
