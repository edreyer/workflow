package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.ensure
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

  sealed interface OrderError : WorkflowError {
    data object EmptyOrder : OrderError
  }

  private val discount = Key<Int>("discount")

  class ValidateDraft : Workflow<Draft, Validated> {
    override val id = "validate-draft"

    override suspend fun WorkflowStepScope.execute(input: Draft): Validated {
      ensure(input.quantity > 0) { OrderError.EmptyOrder }
      emit(DraftValidated(input.quantity))
      return Validated(input.quantity)
    }
  }

  @Test
  fun `class workflow returns state and events`() = runTest {
    val result = ValidateDraft()(Draft(2))

    val value = assertIs<Either.Right<WorkflowResult<Validated>>>(result).value
    assertEquals(Validated(2), value.state)
    assertEquals(listOf(2), value.events.filterIsInstance<DraftValidated>().map { it.quantity })
  }

  @Test
  fun `raised application errors are returned unwrapped`() = runTest {
    assertEquals(Either.Left(OrderError.EmptyOrder), ValidateDraft()(Draft(0)))
  }

  @Test
  fun `lambda workflow can read and put context`() = runTest {
    val applyCoupon = workflow<Draft, Draft>("apply-coupon") { draft ->
      val coupon = ensureNotNull(draft.coupon) { WorkflowError.ValidationError("coupon required") }
      put(discount, coupon.length)
      draft
    }

    val result = applyCoupon(Draft(1, "TEN"), WorkflowContext.of(WorkflowContext.CORRELATION_ID to "corr-1"))

    val value = assertIs<Either.Right<WorkflowResult<Draft>>>(result).value
    assertEquals(3, value.context[discount])
    assertEquals("corr-1", value.context[WorkflowContext.CORRELATION_ID])
  }

  @Test
  fun `thrown exceptions become ExceptionError`() = runTest {
    val boom = workflow<Draft, Draft>("boom") { error("boom") }

    val error = assertIs<WorkflowError.ExceptionError>(assertIs<Either.Left<WorkflowError>>(boom(Draft(1))).value)
    assertEquals("boom", error.ex.message)
  }

  @Test
  fun `emit and put are safe from concurrent coroutines inside a step`() = runTest {
    val fanOut = workflow<Int, Int>("fan-out") { count ->
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
    val describe: Workflow<Any, String> = workflow("describe") { input -> input.toString() }
    val forDrafts: Workflow<Draft, String> = describe

    assertEquals("Draft(quantity=1, coupon=null)", forDrafts(Draft(1)).getOrNull()?.state)
  }
}
