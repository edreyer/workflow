package io.liquidsoftware.workflow.recipes.events

import arrow.core.Either
import arrow.core.raise.ensure
import io.liquidsoftware.workflow.BaseEvent
import io.liquidsoftware.workflow.Event
import io.liquidsoftware.workflow.Failure
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.useCase
import io.liquidsoftware.workflow.workflow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// Recipe: docs/recipes/domain-events.md

data class PlaceOrder(val orderId: String, val total: Long)
data class Order(val id: String, val total: Long)
data class OrderDto(val id: String)

data class OrderPlaced(val orderId: String, val total: Long) : BaseEvent()
data class LoyaltyPointsEarned(val orderId: String, val points: Long) : BaseEvent()

sealed interface OrderError {
  data object EmptyOrder : OrderError
}

// ---- Publishing after success ---------------------------------------------------------------

/** Your application's event bus: Spring's ApplicationEventPublisher, a Kafka producer, and so on. */
fun interface EventBus {
  suspend fun publish(event: Event)
}

data class WithEvents<out O>(val output: O, val events: List<Event>)

fun <I, E, O> UseCase<I, E, WithEvents<O>>.publishingTo(bus: EventBus): UseCase<I, E, O> =
  UseCase { input, context ->
    this(input, context).map { (output, events) ->
      events.forEach { bus.publish(it) }
      output
    }
  }

val createOrder = workflow<PlaceOrder, Order, OrderError>("create-order") { command ->
  ensure(command.total > 0) { OrderError.EmptyOrder }
  emit(OrderPlaced(command.orderId, command.total))
  Order(command.orderId, command.total)
}

val awardPoints = workflow<Order, Order, Nothing>("award-points") { order ->
  emit(LoyaltyPointsEarned(order.id, order.total / 100))
  order
}

fun placeOrder(bus: EventBus): UseCase<PlaceOrder, Failure<OrderError>, OrderDto> =
  useCase<PlaceOrder, OrderError, WithEvents<OrderDto>>("PlaceOrder") {
    startWith { command -> command }
      .then(createOrder)
      .then(awardPoints)
      .result { order -> WithEvents(OrderDto(order.id), events) }
  }.publishingTo(bus)

// ---- Writing an outbox in the same transaction ----------------------------------------------

/** Stands in for your database: one transaction around the order row and the outbox rows. */
class Database {
  val orders = mutableListOf<Order>()
  val outbox = mutableListOf<Event>()

  suspend fun <T> transaction(block: suspend Database.() -> T): T = block()
}

fun saveOrder(db: Database) = workflow<PlaceOrder, Order, OrderError>("save-order") { command ->
  ensure(command.total > 0) { OrderError.EmptyOrder }
  val order = Order(command.orderId, command.total)
  val placed = OrderPlaced(order.id, order.total)
  db.transaction {
    orders += order
    outbox += placed
  }
  emit(placed)
  order
}

fun placeOrderWithOutbox(db: Database): UseCase<PlaceOrder, Failure<OrderError>, OrderDto> =
  useCase("PlaceOrder") {
    startWith { command -> command }
      .then(saveOrder(db))
      .result { order -> OrderDto(order.id) }
  }

class DomainEventsRecipeTest {
  @Test
  fun `publishes every event once the use case succeeds`() = runTest {
    val published = mutableListOf<Event>()
    val result = placeOrder { published += it }(PlaceOrder("o-1", 2_500))

    assertEquals(Either.Right(OrderDto("o-1")), result)
    assertEquals(listOf("OrderPlaced", "LoyaltyPointsEarned"), published.map { it::class.simpleName })
  }

  @Test
  fun `publishes nothing when the use case fails`() = runTest {
    val published = mutableListOf<Event>()
    val result = placeOrder { published += it }(PlaceOrder("o-1", 0))

    assertIs<Either.Left<Failure<OrderError>>>(result)
    assertEquals(emptyList(), published)
  }

  @Test
  fun `the outbox row is written with the order`() = runTest {
    val db = Database()
    placeOrderWithOutbox(db)(PlaceOrder("o-1", 2_500))

    assertEquals(listOf(Order("o-1", 2_500)), db.orders)
    assertEquals(listOf<Event>(OrderPlaced("o-1", 2_500)), db.outbox)
  }
}
