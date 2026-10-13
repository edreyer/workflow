package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.context.ensure
import arrow.core.raise.ensureNotNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkflowTest {

  data class Draft(val quantity: Int, val coupon: String? = null)
  data class Validated(val quantity: Int)
  data class DraftValidated(val quantity: Int) : BaseEvent()

  sealed interface OrderError {
    data object EmptyOrder : OrderError
    data object CouponRequired : OrderError
  }

  /** Domain code with no dependency on this library. */
  class DraftValidator {
    context(_: Raise<OrderError>)
    fun validate(draft: Draft): Validated {
      ensure(draft.quantity > 0) { OrderError.EmptyOrder }
      return Validated(draft.quantity)
    }
  }

  private val validateDraft = workflow<Draft, Validated, OrderError>("validate-draft") { draft ->
    DraftValidator().validate(draft).also { emit(DraftValidated(it.quantity)) }
  }

  @Test
  fun `workflow calling a domain function returns state and events`() = runTest {
    val result = validateDraft(Draft(2))

    val value = assertIs<Either.Right<WorkflowResult<Validated>>>(result).value
    assertEquals(Validated(2), value.state)
    assertEquals(listOf(2), value.events.filterIsInstance<DraftValidated>().map { it.quantity })
  }

  @Test
  fun `raised application errors are returned as Failure Raised`() = runTest {
    assertEquals(Either.Left(Failure.Raised(OrderError.EmptyOrder)), validateDraft(Draft(0)))
  }

  @Test
  fun `lambda workflow can read context`() = runTest {
    val applyCoupon = workflow<Draft, String, OrderError>("apply-coupon") { draft ->
      val coupon = ensureNotNull(draft.coupon) { OrderError.CouponRequired }
      "$coupon:${context[WorkflowContext.CORRELATION_ID]}"
    }

    val result = applyCoupon(Draft(1, "TEN"), WorkflowContext.of(WorkflowContext.CORRELATION_ID to "corr-1"))

    val value = assertIs<Either.Right<WorkflowResult<String>>>(result).value
    assertEquals("TEN:corr-1", value.state)
    assertEquals("corr-1", value.context[WorkflowContext.CORRELATION_ID])
  }

  @Test
  fun `thrown exceptions become Failure Unexpected`() = runTest {
    val boom = workflow<Draft, Draft, Nothing>("boom") { error("boom") }

    val failure = assertIs<Failure.Unexpected>(assertIs<Either.Left<Failure<Nothing>>>(boom(Draft(1))).value)
    assertEquals("boom", failure.cause.message)
  }

  @Test
  fun `emit is safe from concurrent coroutines inside a step`() = runTest {
    val fanOut = workflow<Int, Int, Nothing>("fan-out") { count ->
      coroutineScope {
        (1..count).map { n -> async { emit(DraftValidated(n)) } }.awaitAll()
      }
      count
    }

    val value = assertIs<Either.Right<WorkflowResult<Int>>>(fanOut(100)).value
    assertEquals((1..100).toSet(), value.events.filterIsInstance<DraftValidated>().map { it.quantity }.toSet())
  }

  @Test
  fun `workflow input is contravariant so a supertype step fits`() = runTest {
    val describe: Workflow<Any, String, Nothing> = workflow("describe") { input -> input.toString() }
    val forDrafts: Workflow<Draft, String, OrderError> = describe

    assertEquals("Draft(quantity=1, coupon=null)", forDrafts(Draft(1)).getOrNull()?.state)
  }

  @Test
  fun `mapError adapts a step written for another error type`() = runTest {
    data class PaymentError(val reason: String)
    val charge = workflow<Draft, Draft, PaymentError>("charge") { draft ->
      emit(DraftValidated(draft.quantity))
      raise(PaymentError("declined"))
    }

    val adapted: Workflow<Draft, Draft, OrderError> = charge.mapError { OrderError.EmptyOrder }

    assertEquals("charge", adapted.id)
    assertEquals(Either.Left(Failure.Raised(OrderError.EmptyOrder)), adapted(Draft(1)))
  }
}
