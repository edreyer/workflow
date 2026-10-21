package io.liquidsoftware.workflow.tutorial.ch05

import arrow.core.Either
import io.liquidsoftware.workflow.BaseEvent
import io.liquidsoftware.workflow.Event
import io.liquidsoftware.workflow.Failure
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.Workflow
import io.liquidsoftware.workflow.lastEvent
import io.liquidsoftware.workflow.invoke
import io.liquidsoftware.workflow.useCase
import io.liquidsoftware.workflow.workflow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// Tutorial chapter 5: docs/tutorial/05-events.md

data class PlaceOrder(val customerId: String, val total: Long)
data class Order(val id: String, val customerId: String, val total: Long)
data class OrderConfirmation(val orderId: String, val total: Long, val loyaltyPoints: Int)

// Events record facts. Extend BaseEvent for a generated id and timestamp, or implement Event.
data class OrderCreated(val orderId: String, val customerId: String) : BaseEvent()
data class LoyaltyPointsEarned(val customerId: String, val points: Int) : Event

class Orders {
  private var next = 0
  fun create(customerId: String, total: Long): Order = Order("o-${++next}", customerId, total)
}

fun createOrder(orders: Orders): Workflow<PlaceOrder, Order, Nothing> =
  workflow("create-order") { command ->
    val order = orders.create(command.customerId, command.total)
    emit(OrderCreated(order.id, order.customerId))
    order
  }

val awardPoints: Workflow<Order, Order, Nothing> =
  workflow("award-points") { order ->
    emit(LoyaltyPointsEarned(order.customerId, points = (order.total / 100).toInt()))
    order
  }

// The result block sees every event the pipeline emitted.
fun placeOrder(orders: Orders): UseCase<PlaceOrder, Failure<Nothing>, OrderConfirmation> =
  useCase("PlaceOrder") {
    startWith { command -> command }
      .then(createOrder(orders))
      .then(awardPoints)
      .result { order ->
        val points = events.lastEvent<LoyaltyPointsEarned>()?.points ?: 0
        OrderConfirmation(order.id, order.total, points)
      }
  }

// Return the events too, so the caller can publish them after its transaction commits.
data class Placed(val confirmation: OrderConfirmation, val events: List<Event>)

fun placeOrderWithEvents(orders: Orders): UseCase<PlaceOrder, Failure<Nothing>, Placed> =
  useCase("PlaceOrder") {
    startWith { command -> command }
      .then(createOrder(orders))
      .then(awardPoints)
      .result { order ->
        // requireEvent fails the use case as wired wrongly if no step emitted the event.
        val created = requireEvent<OrderCreated>()
        Placed(OrderConfirmation(created.orderId, order.total, 0), events)
      }
  }

class Ch05EventsTest {
  @Test
  fun `the result reads the events steps emitted`() = runTest {
    assertEquals(Either.Right(OrderConfirmation("o-1", 2_500, 25)), placeOrder(Orders())(PlaceOrder("c-1", 2_500)))
  }

  @Test
  fun `events can be returned to the caller`() = runTest {
    val placed = assertIs<Either.Right<Placed>>(placeOrderWithEvents(Orders())(PlaceOrder("c-1", 2_500))).value

    assertEquals(listOf(OrderCreated::class, LoyaltyPointsEarned::class), placed.events.map { it::class })
  }

  @Test
  fun `a step run on its own returns its events`() = runTest {
    val result = createOrder(Orders())(PlaceOrder("c-1", 100)).getOrNull()!!

    assertEquals(listOf<Event>(OrderCreated("o-1", "c-1")), result.events)
  }
}
