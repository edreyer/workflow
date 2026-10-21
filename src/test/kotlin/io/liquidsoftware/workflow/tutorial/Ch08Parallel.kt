package io.liquidsoftware.workflow.tutorial.ch08

import arrow.core.Either
import arrow.core.raise.ensure
import io.liquidsoftware.workflow.Event
import io.liquidsoftware.workflow.Failure
import io.liquidsoftware.workflow.ParallelErrorPolicy
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.Workflow
import io.liquidsoftware.workflow.parallelJoin
import io.liquidsoftware.workflow.invoke
import io.liquidsoftware.workflow.useCase
import io.liquidsoftware.workflow.workflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Tutorial chapter 8: docs/tutorial/08-parallel-steps.md

data class PlaceOrder(val customerId: String, val skus: List<String>)
data class Customer(val id: String, val tier: String)
data class Prices(val total: Long)
data class Stock(val available: Boolean)
data class Checkout(val command: PlaceOrder, val customer: Customer, val total: Long)
data class OrderConfirmation(val customerId: String, val tier: String, val total: Long)

data class WarehouseNotified(val customerId: String) : Event
data class AnalyticsRecorded(val customerId: String) : Event

sealed interface OrderError {
  data object UnknownCustomer : OrderError
  data object OutOfStock : OrderError
}

// Three lookups that each take 100 ms and don't depend on each other.
val loadCustomer: Workflow<PlaceOrder, Customer, OrderError> =
  workflow("load-customer") { command ->
    delay(100)
    ensure(command.customerId != "unknown") { OrderError.UnknownCustomer }
    Customer(command.customerId, tier = "gold")
  }

val priceItems: Workflow<PlaceOrder, Prices, Nothing> =
  workflow("price-items") { command -> delay(100); Prices(command.skus.size * 1_000L) }

val checkStock: Workflow<PlaceOrder, Stock, OrderError> =
  workflow("check-stock") { command ->
    delay(100)
    ensure("sold-out" !in command.skus) { OrderError.OutOfStock }
    Stock(available = true)
  }

// Side effects whose output nobody needs.
val notifyWarehouse: Workflow<Checkout, Unit, Nothing> =
  workflow("notify-warehouse") { checkout -> delay(50); emit(WarehouseNotified(checkout.customer.id)) }

val recordAnalytics: Workflow<Checkout, Unit, Nothing> =
  workflow("record-analytics") { checkout -> delay(50); emit(AnalyticsRecorded(checkout.customer.id)) }

val placeOrder: UseCase<PlaceOrder, Failure<OrderError>, Pair<OrderConfirmation, List<Event>>> =
  useCase("PlaceOrder") {
    startWith { command -> command }
      // Run all three at once and merge their outputs into the next state. `this` is the PlaceOrder.
      .parallelJoin(loadCustomer, priceItems, checkStock) { customer, prices, _ ->
        Checkout(this, customer, prices.total)
      }
      // Run side effects at once and keep the current state.
      .parallel(notifyWarehouse, recordAnalytics)
      .result { checkout -> OrderConfirmation(checkout.customer.id, checkout.customer.tier, checkout.total) to events }
  }

// A join can be named and reused like any other step.
val prepareCheckout: Workflow<PlaceOrder, Checkout, OrderError> =
  parallelJoin(loadCustomer, priceItems, policy = ParallelErrorPolicy.FailFast) { customer, prices ->
    Checkout(this, customer, prices.total)
  }

class Ch08ParallelTest {
  @Test
  fun `parallel steps run concurrently`() = runTest {
    val (confirmation, events) = placeOrder(PlaceOrder("c-1", listOf("book", "pen"))).getOrNull()!!

    assertEquals(OrderConfirmation("c-1", "gold", 2_000), confirmation)
    // Events are merged in parameter order, whichever branch finished first.
    assertEquals(listOf<Event>(WarehouseNotified("c-1"), AnalyticsRecorded("c-1")), events)
    assertEquals(150, currentTime) // 100 ms for the join plus 50 ms for the side effects
  }

  @Test
  fun `a failing branch fails the use case`() = runTest {
    assertEquals(
      Either.Left(Failure.Raised(OrderError.OutOfStock)),
      placeOrder(PlaceOrder("c-1", listOf("sold-out"))).map { it.first },
    )
  }

  @Test
  fun `a named join runs like a step`() = runTest {
    assertEquals(
      Either.Left(Failure.Raised(OrderError.UnknownCustomer)),
      prepareCheckout(PlaceOrder("unknown", listOf("book"))).map { it.state },
    )
  }
}
