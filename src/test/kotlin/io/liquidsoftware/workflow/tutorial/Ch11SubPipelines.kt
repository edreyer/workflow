package io.liquidsoftware.workflow.tutorial.ch11

import arrow.core.Either
import arrow.core.raise.ensure
import io.liquidsoftware.workflow.Failure
import io.liquidsoftware.workflow.Pipeline
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.UseCaseSummary
import io.liquidsoftware.workflow.pipeline
import io.liquidsoftware.workflow.useCase
import io.liquidsoftware.workflow.useCaseConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Tutorial chapter 11: docs/tutorial/11-sub-pipelines.md

data class PlaceOrder(val customerId: String, val skus: List<String>)
data class Reorder(val previousOrderId: String)
data class PaidOrder(val orderId: String, val skus: List<String>)
data class PackedOrder(val order: PaidOrder, val parcels: Int)
data class Shipment(val orderId: String, val trackingNumber: String)

sealed interface OrderError {
  data object EmptyOrder : OrderError
  data object UnknownOrder : OrderError
}

// The steps after payment are the same whichever way the order came in.
val fulfilOrder: Pipeline<PaidOrder, OrderError, Shipment> =
  pipeline<PaidOrder, OrderError>()
    .guard("check-items") { order -> ensure(order.skus.isNotEmpty()) { OrderError.EmptyOrder } }
    .then("pack") { order -> PackedOrder(order, parcels = (order.skus.size + 2) / 3) }
    .then("ship") { packed -> Shipment(packed.order.orderId, "TRACK-${packed.order.orderId}-${packed.parcels}") }

private val summaries = mutableListOf<UseCaseSummary>()
private val config = useCaseConfig { onSummary { summaries += it } }

val placeOrder: UseCase<PlaceOrder, Failure<OrderError>, Shipment> =
  useCase("PlaceOrder", config = config) {
    startWith { command -> command }
      .then("take-payment") { command -> PaidOrder("o-${command.customerId}", command.skus) }
      .then(fulfilOrder)
      .result { shipment -> shipment }
  }

val reorder: UseCase<Reorder, Failure<OrderError>, Shipment> =
  useCase("Reorder", config = config) {
    startWith { command -> command }
      .then("load-previous-order") { command ->
        ensure(command.previousOrderId == "o-1") { OrderError.UnknownOrder }
        PaidOrder("o-1-again", listOf("book", "pen"))
      }
      .then(fulfilOrder)
      .result { shipment -> shipment }
  }

class Ch11SubPipelinesTest {
  @Test
  fun `two use cases share the fulfilment steps`() = runTest {
    assertEquals(Either.Right(Shipment("o-c1", "TRACK-o-c1-2")), placeOrder(PlaceOrder("c1", listOf("a", "b", "c", "d"))))
    assertEquals(Either.Right(Shipment("o-1-again", "TRACK-o-1-again-1")), reorder(Reorder("o-1")))
  }

  @Test
  fun `the sub-pipeline's steps keep their own ids`() = runTest {
    summaries.clear()
    placeOrder(PlaceOrder("c1", listOf("a")))

    assertEquals(listOf("take-payment", "check-items", "pack", "ship"), summaries.single().executions.map { it.workflowId })
  }
}
