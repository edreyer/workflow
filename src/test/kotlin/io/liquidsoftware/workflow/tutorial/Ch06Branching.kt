package io.liquidsoftware.workflow.tutorial.ch06

import arrow.core.Either
import io.liquidsoftware.workflow.Failure
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.Workflow
import io.liquidsoftware.workflow.useCase
import io.liquidsoftware.workflow.workflow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Tutorial chapter 6: docs/tutorial/06-branching.md

data class PlaceOrder(val customerId: String, val total: Long, val member: Boolean, val express: Boolean)
data class Order(val customerId: String, val total: Long, val member: Boolean, val express: Boolean)
data class ShippedOrder(val order: Order, val carrier: String, val days: Int)
data class OrderConfirmation(val total: Long, val carrier: String, val days: Int)

// thenIf may skip the step, so the step must keep the state type.
val memberDiscount: Workflow<Order, Order, Nothing> =
  workflow("member-discount") { order -> order.copy(total = order.total - order.total / 10) }

// branch runs exactly one of two steps that produce the same next state type.
val expressShipping: Workflow<Order, ShippedOrder, Nothing> =
  workflow("express-shipping") { order -> ShippedOrder(order, carrier = "courier", days = 1) }

val standardShipping: Workflow<Order, ShippedOrder, Nothing> =
  workflow("standard-shipping") { order -> ShippedOrder(order, carrier = "post", days = 4) }

val placeOrder: UseCase<PlaceOrder, Failure<Nothing>, OrderConfirmation> =
  useCase("PlaceOrder") {
    startWith { command -> Order(command.customerId, command.total, command.member, command.express) }
      .thenIf(memberDiscount) { order -> order.member }
      .branch(ifTrue = expressShipping, ifFalse = standardShipping) { order -> order.express }
      .result { shipped -> OrderConfirmation(shipped.order.total, shipped.carrier, shipped.days) }
  }

class Ch06BranchingTest {
  @Test
  fun `members get a discount and express orders ship by courier`() = runTest {
    assertEquals(
      Either.Right(OrderConfirmation(900, "courier", 1)),
      placeOrder(PlaceOrder("c-1", 1_000, member = true, express = true)),
    )
    assertEquals(
      Either.Right(OrderConfirmation(1_000, "post", 4)),
      placeOrder(PlaceOrder("c-2", 1_000, member = false, express = false)),
    )
  }
}
