package io.liquidsoftware.workflow.tutorial.ch02

import arrow.core.Either
import arrow.core.raise.ensure
import io.liquidsoftware.workflow.Failure
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.useCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// Tutorial chapter 2: docs/tutorial/02-typed-errors.md

data class PlaceOrder(val customerId: String, val items: List<LineItem>)
data class LineItem(val sku: String, val quantity: Int)
data class Cart(val customerId: String, val items: List<LineItem>)
data class PricedCart(val cart: Cart, val total: Long)
data class OrderConfirmation(val customerId: String, val total: Long)

val prices = mapOf("book" to 1_500L, "pen" to 250L)

// The use case's own errors: a sealed hierarchy with no library supertype.
sealed interface OrderError {
  data object EmptyCart : OrderError
  data class InvalidQuantity(val sku: String) : OrderError
}

// The error type callers see, such as an HTTP problem.
data class ApiError(val status: Int, val code: String)

fun toApiError(failure: Failure<OrderError>): ApiError =
  when (failure) {
    is Failure.Raised -> when (val error = failure.error) {
      OrderError.EmptyCart -> ApiError(400, "empty-cart")
      is OrderError.InvalidQuantity -> ApiError(400, "invalid-quantity:${error.sku}")
    }
    is Failure.Unexpected -> ApiError(500, "internal")
  }

val placeOrder: UseCase<PlaceOrder, ApiError, OrderConfirmation> =
  useCase("PlaceOrder", errors = ::toApiError) {
    startWith { command ->
      ensure(command.items.isNotEmpty()) { OrderError.EmptyCart }
      command.items.firstOrNull { it.quantity <= 0 }?.let { raise(OrderError.InvalidQuantity(it.sku)) }
      Cart(command.customerId, command.items)
    }
      .then("price-cart") { cart ->
        PricedCart(cart, cart.items.sumOf { prices.getValue(it.sku) * it.quantity })
      }
      .result { priced -> OrderConfirmation(priced.cart.customerId, priced.total) }
  }

// Without a mapper, callers get the Failure itself.
val placeOrderRaw: UseCase<PlaceOrder, Failure<OrderError>, OrderConfirmation> =
  useCase("PlaceOrder") {
    startWith { command ->
      ensure(command.items.isNotEmpty()) { OrderError.EmptyCart }
      Cart(command.customerId, command.items)
    }
      .result { cart -> OrderConfirmation(cart.customerId, 0) }
  }

class Ch02TypedErrorsTest {
  @Test
  fun `raised errors are mapped to the caller's error type`() = runTest {
    assertEquals(Either.Left(ApiError(400, "empty-cart")), placeOrder(PlaceOrder("c-1", emptyList())))
    assertEquals(
      Either.Left(ApiError(400, "invalid-quantity:pen")),
      placeOrder(PlaceOrder("c-1", listOf(LineItem("pen", 0)))),
    )
  }

  @Test
  fun `a thrown exception is an unexpected failure`() = runTest {
    // getValue throws for an unknown SKU. Chapter 3 turns this into a typed error.
    assertEquals(Either.Left(ApiError(500, "internal")), placeOrder(PlaceOrder("c-1", listOf(LineItem("mug", 1)))))
  }

  @Test
  fun `without a mapper the use case returns the Failure`() = runTest {
    assertEquals(Either.Left(Failure.Raised(OrderError.EmptyCart)), placeOrderRaw(PlaceOrder("c-1", emptyList())))
    assertIs<Either.Right<OrderConfirmation>>(placeOrderRaw(PlaceOrder("c-1", listOf(LineItem("pen", 1)))))
  }
}
