package io.liquidsoftware.workflow.tutorial.ch07

import arrow.core.Either
import arrow.core.raise.ensure
import io.liquidsoftware.workflow.Failure
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.Workflow
import io.liquidsoftware.workflow.mapInput
import io.liquidsoftware.workflow.mapOutput
import io.liquidsoftware.workflow.invoke
import io.liquidsoftware.workflow.useCase
import io.liquidsoftware.workflow.workflow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Tutorial chapter 7: docs/tutorial/07-focused-steps.md

data class PlaceOrder(val customerId: String, val address: Address, val skus: List<String>)
data class Address(val country: String, val postcode: String)

// The state grows as the use case runs. Each step only needs part of it.
data class Checkout(val customerId: String, val address: Address, val skus: List<String>)
data class ReservedCheckout(val checkout: Checkout, val reservation: Reservation)
data class ReadyCheckout(val checkout: Checkout, val reservation: Reservation, val shippingCost: Long)

data class OrderConfirmation(val reservationId: String, val shippingCost: Long)

sealed interface OrderError {
  data object OutOfStock : OrderError
  data class CannotShipTo(val country: String) : OrderError
}

// Steps that know nothing about Checkout. They might live in other modules.
data class Reservation(val id: String)

val reserveStock: Workflow<List<String>, Reservation, OrderError> =
  workflow("reserve-stock") { skus ->
    ensure("sold-out" !in skus) { OrderError.OutOfStock }
    Reservation("r-${skus.size}")
  }

data class Quote(val carrier: String, val cents: Long)

val quoteShipping: Workflow<Address, Quote, OrderError> =
  workflow("quote-shipping") { address ->
    ensure(address.country in setOf("NL", "DE")) { OrderError.CannotShipTo(address.country) }
    Quote("post", if (address.country == "NL") 495 else 895)
  }

val placeOrder: UseCase<PlaceOrder, Failure<OrderError>, OrderConfirmation> =
  useCase("PlaceOrder") {
    startWith { command -> Checkout(command.customerId, command.address, command.skus) }
      // input picks what the step needs. The merge block builds the next state; `this` is the current one.
      .then(reserveStock, input = { checkout -> checkout.skus }) { reservation -> ReservedCheckout(this, reservation) }
      .then(quoteShipping, input = { it.checkout.address }) { quote ->
        ReadyCheckout(checkout, reservation, shippingCost = quote.cents)
      }
      .result { ready -> OrderConfirmation(ready.reservation.id, ready.shippingCost) }
  }

// mapInput and mapOutput adapt a step once, so the adapted step can be passed around like any other.
val shippingCostOf: Workflow<Checkout, Long, OrderError> =
  quoteShipping
    .mapInput { checkout: Checkout -> checkout.address }
    .mapOutput { quote -> quote.cents }

class Ch07FocusedStepsTest {
  @Test
  fun `steps run on part of the state`() = runTest {
    assertEquals(
      Either.Right(OrderConfirmation("r-2", 495)),
      placeOrder(PlaceOrder("c-1", Address("NL", "1011"), listOf("book", "pen"))),
    )
    assertEquals(
      Either.Left(Failure.Raised(OrderError.CannotShipTo("US"))),
      placeOrder(PlaceOrder("c-1", Address("US", "10001"), listOf("book"))),
    )
  }

  @Test
  fun `adapted steps keep their id`() = runTest {
    val checkout = Checkout("c-1", Address("DE", "10115"), listOf("book"))

    assertEquals("quote-shipping", shippingCostOf.id)
    assertEquals(Either.Right(895L), shippingCostOf(checkout).map { it.state })
  }
}
