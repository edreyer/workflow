package io.liquidsoftware.workflow

import arrow.core.Either
import kotlinx.coroutines.test.runTest
import org.slf4j.MDC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompositionTest {

  data class Order(val id: String, val quantity: Int, val express: Boolean = false)
  data class Checkout(val order: Order, val reservation: String? = null)
  data class Shipment(val orderId: String, val method: String)
  data class Shipped(val orderId: String) : BaseEvent()

  sealed interface AppError {
    data class Order(val reason: String) : AppError
    data object Other : AppError
  }

  private val summaries = mutableListOf<UseCaseSummary>()
  private val config = useCaseConfig { onSummary { summaries += it } }

  private val express = workflow<Order, Shipment, Nothing>("express") { Shipment(it.id, "express") }
  private val standard = workflow<Order, Shipment, Nothing>("standard") { Shipment(it.id, "standard") }

  @Test
  fun `branch runs ifTrue when the predicate matches and ifFalse otherwise`() = runTest {
    val ship: UseCase<Order, Failure<Nothing>, String> = useCase("Ship", config = config) {
      startWith { it }
        .branch(ifTrue = express, ifFalse = standard) { order -> order.express }
        .result { it.method }
    }

    assertEquals(Either.Right("express"), ship(Order("o-1", 1, express = true)))
    assertEquals(Either.Right("standard"), ship(Order("o-2", 1)))
    assertEquals(listOf(listOf("express"), listOf("standard")), summaries.map { s -> s.executions.map { it.workflowId } })
  }

  @Test
  fun `branch predicate exceptions become Failure Unexpected`() = runTest {
    val ship: UseCase<Order, Failure<Nothing>, String> = useCase("Ship") {
      startWith { it }
        .branch(express, standard) { error("boom") }
        .result { it.method }
    }

    assertEquals("boom", (ship(Order("o-1", 1)).leftOrNull() as Failure.Unexpected).cause.message)
  }

  @Test
  fun `focused then runs a step on part of the state and merges its output back`() = runTest {
    val reserve = workflow<Order, String, AppError>("reserve") { order ->
      if (order.quantity <= 0) raise(AppError.Order("empty"))
      "r-${order.id}"
    }
    val checkout: UseCase<Order, Failure<AppError>, Checkout> = useCase("Checkout", config = config) {
      startWith { Checkout(it) }
        .then(reserve, input = { it.order }) { reservation -> copy(reservation = reservation) }
        .result { it }
    }

    assertEquals(Either.Right(Checkout(Order("o-1", 2), "r-o-1")), checkout(Order("o-1", 2)))
    assertEquals(Either.Left(Failure.Raised(AppError.Order("empty"))), checkout(Order("o-2", 0)))
    assertEquals("reserve", summaries.last().rootError?.workflowId)
  }

  @Test
  fun `a reusable pipeline appends its steps with their own ids and a narrower error type`() = runTest {
    val validate = workflow<Order, Order, AppError.Order>("validate") { order ->
      if (order.quantity <= 0) raise(AppError.Order("empty"))
      order
    }
    val shipOrder = workflow<Order, Shipment, Nothing>("ship") { order ->
      emit(Shipped(order.id))
      Shipment(order.id, "standard")
    }
    val fulfil: Pipeline<Order, AppError.Order, Shipment> = pipeline<Order, AppError.Order>()
      .then(validate)
      .then(shipOrder)

    val placeOrder: UseCase<Order, Failure<AppError>, Pair<String, Int>> = useCase("PlaceOrder", config = config) {
      startWith { it }
        .then(fulfil)
        .result { shipment -> shipment.orderId to events.size }
    }

    assertEquals(Either.Right("o-1" to 1), placeOrder(Order("o-1", 1)))
    assertEquals(listOf("validate", "ship"), summaries.single().executions.map { it.workflowId })
    assertEquals(Either.Left(Failure.Raised(AppError.Order("empty"))), placeOrder(Order("o-2", 0)))
    assertEquals("validate", summaries.last().rootError?.workflowId)
  }

  @Test
  fun `mapInput and mapOutput adapt a step and keep its id`() = runTest {
    val quantity = workflow<Int, Int, Nothing>("double") { it * 2 }
    val adapted = quantity.mapInput { order: Order -> order.quantity }.mapOutput { "x$it" }

    assertEquals("double", adapted.id)
    assertEquals(Either.Right(WorkflowResult("x6")), adapted(Order("o-1", 3)))
  }

  @Test
  fun `the correlation id is in the MDC while the use case runs`() = runTest {
    val seen: UseCase<Order, Failure<Nothing>, String?> = useCase("Mdc") {
      startWith { it }
        .then("read-mdc") { MDC.get("correlationId") }
        .result { it }
    }

    assertEquals(Either.Right("corr-1"), seen(Order("o-1", 1), WorkflowContext.of(WorkflowContext.CORRELATION_ID to "corr-1")))
    assertEquals(Either.Right(null), seen(Order("o-1", 1)))
    assertNull(MDC.get("correlationId"))
  }
}
