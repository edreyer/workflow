package io.liquidsoftware.workflow.tutorial.ch12

import arrow.core.Either
import io.liquidsoftware.workflow.Failure
import io.liquidsoftware.workflow.Key
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.UseCaseSummary
import io.liquidsoftware.workflow.Workflow
import io.liquidsoftware.workflow.WorkflowContext
import io.liquidsoftware.workflow.useCase
import io.liquidsoftware.workflow.useCaseConfig
import io.liquidsoftware.workflow.workflow
import kotlinx.coroutines.test.runTest
import org.slf4j.MDC
import kotlin.test.Test
import kotlin.test.assertEquals

// Tutorial chapter 12: docs/tutorial/12-context.md

data class Order(val id: String, val total: Long)
data class OrderConfirmation(val orderId: String, val total: Long, val giftWrapped: Boolean)

// Keys compare by identity: declare each one once and share it.
object RequestKeys {
  val TENANT = Key<String>("tenant")
  val FEATURES = Key<Set<String>>("features")
}

// A domain function asks for what it needs as a context parameter instead of reading keys.
data class Tenant(val id: String)

context(tenant: Tenant)
fun priceFor(order: Order): Long = if (tenant.id == "wholesale") order.total * 8 / 10 else order.total

val giftWrap: Workflow<OrderConfirmation, OrderConfirmation, Nothing> =
  workflow("gift-wrap") { confirmation -> confirmation.copy(giftWrapped = true) }

private val logged = mutableListOf<String?>()

val placeOrder: UseCase<Order, Failure<Nothing>, OrderConfirmation> =
  useCase("PlaceOrder") {
    startWith { order -> order }
      .then("price") { order ->
        logged += MDC.get("correlationId") // your own log lines carry the correlation id
        val tenant = Tenant(context[RequestKeys.TENANT] ?: "retail")
        OrderConfirmation(order.id, context(tenant) { priceFor(order) }, giftWrapped = false)
      }
      // Predicates read the context too, here a feature flag.
      .thenIf(giftWrap) { "gift-wrap" in context[RequestKeys.FEATURES].orEmpty() }
      .result { it }
  }

class Ch12ContextTest {
  @Test
  fun `steps and predicates read the request context`() = runTest {
    val order = Order("o-1", 1_000)
    val context = WorkflowContext.of(
      WorkflowContext.CORRELATION_ID to "req-42",
      RequestKeys.TENANT to "wholesale",
      RequestKeys.FEATURES to setOf("gift-wrap"),
    )

    assertEquals(Either.Right(OrderConfirmation("o-1", 800, giftWrapped = true)), placeOrder(order, context))
    assertEquals(Either.Right(OrderConfirmation("o-1", 1_000, giftWrapped = false)), placeOrder(order))
    assertEquals(listOf("req-42", null), logged)
  }

  @Test
  fun `the correlation id is in the summary`() = runTest {
    val summaries = mutableListOf<UseCaseSummary>()
    val traced = useCase<Order, Nothing, Order>("Trace", config = useCaseConfig { onSummary { summaries += it } }) {
      startWith { it }.result { it }
    }

    traced(Order("o-1", 1), WorkflowContext.of(WorkflowContext.CORRELATION_ID to "req-7"))

    assertEquals("req-7", summaries.single().correlationId)
  }
}
