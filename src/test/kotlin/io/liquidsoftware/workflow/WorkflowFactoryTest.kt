package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkflowFactoryTest {

  data class PlaceOrder(val orderId: String, val quantity: Int, val coupon: String? = null) : UseCaseCommand
  data class OrderDraft(val orderId: String, val quantity: Int, val coupon: String?) : WorkflowState
  data class ValidatedOrder(val orderId: String, val quantity: Int) : WorkflowState
  data class PricedOrder(val orderId: String, val total: Int) : WorkflowState

  data class OrderValidated(
    val orderId: String,
    override val id: UUID = UUID.randomUUID(),
    override val timestamp: Instant = Instant.now(),
  ) : Event

  data class OrderError(val code: String)

  private val discountKey = Key.int("discount")

  private val validateOrder = workflow<OrderDraft, ValidatedOrder>("validate-order") { draft ->
    ensure(draft.quantity > 0) { WorkflowError.DomainError("invalid-quantity", "Quantity must be positive") }
    emit(OrderValidated(draft.orderId))
    if (draft.coupon == "TEN") put(discountKey, 10)
    ValidatedOrder(draft.orderId, draft.quantity)
  }

  private val priceOrder = workflow<ValidatedOrder, PricedOrder>("price-order") { order ->
    val discount = context.get(discountKey) ?: 0
    PricedOrder(order.orderId, order.quantity * 100 - discount)
  }

  private val placeOrder: UseCase<PlaceOrder, OrderError, Int> =
    useCase(
      name = "PlaceOrder",
      errors = { error ->
        when (val cause = error.rootCause()) {
          is WorkflowError.DomainError -> OrderError(cause.code)
          else -> OrderError("unexpected")
        }
      },
    ) {
      startWith { command -> Either.Right(OrderDraft(command.orderId, command.quantity, command.coupon)) }
      then(validateOrder)
      then(priceOrder)
      result(state<PricedOrder>()) { it.total }
    }

  @Test
  fun `lambda workflows compose like workflow subclasses`() = runBlocking<Unit> {
    assertEquals(Either.Right(200), placeOrder(PlaceOrder("o-1", 2)))
  }

  @Test
  fun `put makes typed context data visible to later steps`() = runBlocking<Unit> {
    assertEquals(Either.Right(190), placeOrder(PlaceOrder("o-1", 2, coupon = "TEN")))
  }

  @Test
  fun `raised errors reach the error mapper and rootCause unwraps them`() = runBlocking<Unit> {
    assertEquals(Either.Left(OrderError("invalid-quantity")), placeOrder(PlaceOrder("o-1", 0)))
  }

  @Test
  fun `emitted events and execution history are recorded under the workflow id`() = runBlocking<Unit> {
    val detailed = workflowUseCase<PlaceOrder>(name = "PlaceOrder") {
      startWith { command -> Either.Right(OrderDraft(command.orderId, command.quantity, command.coupon)) }
      then(validateOrder)
      then(priceOrder)
    }.executeDetailed(PlaceOrder("o-1", 1))

    val result = assertIs<Either.Right<UseCaseResult<WorkflowState>>>(detailed).value
    assertEquals(listOf("o-1"), result.events.filterIsInstance<OrderValidated>().map { it.orderId })
    assertEquals(listOf("validate-order", "price-order"), result.context.executions.map { it.workflowName })
    assertEquals(listOf("validate-order", "price-order"), result.context.executions.map { it.workflowId })
  }

  @Test
  fun `failures keep the failing workflow id`() = runBlocking<Unit> {
    val result = validateOrder.execute(OrderDraft("o-1", 0, null))

    val error = assertIs<WorkflowError.ExecutionContextError>(assertIs<Either.Left<WorkflowError>>(result).value)
    assertEquals("validate-order", error.execution.workflowId)
    assertIs<WorkflowError.DomainError>(error.rootCause())
  }

  @Test
  fun `thrown exceptions become ExceptionError`() = runBlocking<Unit> {
    val boom = workflow<OrderDraft, OrderDraft>("boom") { error("boom") }

    val result = boom.execute(OrderDraft("o-1", 1, null))

    val cause = assertIs<Either.Left<WorkflowError>>(result).value.rootCause()
    assertIs<WorkflowError.ExceptionError>(cause)
    assertEquals("boom", cause.ex.message)
  }

  @Test
  fun `ensureNotNull smart-casts inside lambda workflows`() = runBlocking<Unit> {
    val requireCoupon = workflow<OrderDraft, OrderDraft>("require-coupon") { draft ->
      val coupon = ensureNotNull(draft.coupon) { WorkflowError.ValidationError("coupon required") }
      draft.copy(coupon = coupon.uppercase())
    }

    val result = requireCoupon.execute(OrderDraft("o-1", 1, "ten"))

    assertEquals("TEN", assertIs<Either.Right<WorkflowResult<OrderDraft>>>(result).value.state.coupon)
  }

  @Test
  fun `rootCause unwraps nested wrappers and leaves plain errors alone`() {
    val domain = WorkflowError.DomainError("code", "message")
    val execution = WorkflowExecution("wf", "wf", Instant.EPOCH, Instant.EPOCH, succeeded = false)

    assertEquals(domain, WorkflowError.ChainError(WorkflowError.ExecutionContextError(domain, execution)).rootCause())
    assertEquals(domain, domain.rootCause())
    assertTrue(WorkflowError.ValidationError("v").rootCause() is WorkflowError.ValidationError)
  }
}
