package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.ensure
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

class UseCaseTest {

  data class PlaceOrder(val orderId: String, val quantity: Int, val priority: Boolean = false)
  data class OrderDraft(val orderId: String, val quantity: Int, val priority: Boolean)
  data class ValidatedOrder(val orderId: String, val quantity: Int, val priority: Boolean)
  data class PricedOrder(val orderId: String, val total: Int, val expedited: Boolean = false)
  data class OrderDto(val orderId: String, val total: Int, val expedited: Boolean)

  data class OrderValidated(val orderId: String) : BaseEvent()
  data class OrderPriced(val total: Int) : BaseEvent()
  data class ReceiptSent(val orderId: String) : BaseEvent()

  sealed interface OrderError {
    data object InvalidOrderId : OrderError
    data object EmptyOrder : OrderError
    data class PriceTooHigh(val total: Int) : OrderError
  }

  data class AppError(val code: String)

  private val tenant = Key<String>("tenant")
  private val audit = Key<List<String>>("audit")

  private val validateOrder = workflow<OrderDraft, ValidatedOrder, OrderError>("validate-order") { draft ->
    ensure(draft.quantity > 0) { OrderError.EmptyOrder }
    emit(OrderValidated(draft.orderId))
    ValidatedOrder(draft.orderId, draft.quantity, draft.priority)
  }

  private val priceOrder = workflow<ValidatedOrder, PricedOrder, OrderError>("price-order") { order ->
    val total = order.quantity * 100
    ensure(total <= 1_000) { OrderError.PriceTooHigh(total) }
    emit(OrderPriced(total))
    PricedOrder(order.orderId, total)
  }

  private val expedite = workflow<PricedOrder, PricedOrder, Nothing>("expedite") { it.copy(total = it.total + 50, expedited = true) }

  private fun toAppError(failure: Failure<OrderError>): AppError =
    when (failure) {
      is Failure.Raised -> when (val error = failure.error) {
        OrderError.InvalidOrderId -> AppError("invalid")
        OrderError.EmptyOrder -> AppError("empty")
        is OrderError.PriceTooHigh -> AppError("too-high:${error.total}")
      }
      is Failure.Unexpected -> AppError("unexpected")
    }

  private val placeOrder: UseCase<PlaceOrder, AppError, OrderDto> =
    useCase(name = "PlaceOrder", errors = ::toAppError) {
      startWith { command -> OrderDraft(command.orderId, command.quantity, command.priority) }
        .then(validateOrder)
        .then(priceOrder)
        .thenIf(expedite) { order -> order.total < 500 && events.lastEvent<OrderValidated>() != null && state == order }
        .result { order -> OrderDto(order.orderId, order.total, order.expedited) }
    }

  @Test
  fun `steps run in order and the result is projected from the typed final state`() = runTest {
    assertEquals(Either.Right(OrderDto("o-1", 600, false)), placeOrder(PlaceOrder("o-1", 6)))
  }

  @Test
  fun `thenIf runs the step only when the predicate matches`() = runTest {
    assertEquals(Either.Right(OrderDto("o-1", 250, true)), placeOrder(PlaceOrder("o-1", 2)))
  }

  @Test
  fun `application errors raised by a step reach the error mapper unchanged`() = runTest {
    assertEquals(Either.Left(AppError("empty")), placeOrder(PlaceOrder("o-1", 0)))
    assertEquals(Either.Left(AppError("too-high:2000")), placeOrder(PlaceOrder("o-1", 20)))
  }

  @Test
  fun `a failing step stops the pipeline`() = runTest {
    var ran = false
    val after = workflow<ValidatedOrder, ValidatedOrder, Nothing>("after") { ran = true; it }
    val useCase: UseCase<PlaceOrder, Failure<OrderError>, String> = useCase("Test") {
      startWith { command -> OrderDraft(command.orderId, command.quantity, command.priority) }
        .then(validateOrder)
        .then(after)
        .result { it.orderId }
    }

    assertEquals(Either.Left(Failure.Raised(OrderError.EmptyOrder)), useCase(PlaceOrder("o-1", 0)))
    assertEquals(false, ran)
  }

  @Test
  fun `startWith can raise validation errors`() = runTest {
    val useCase: UseCase<PlaceOrder, AppError, String> = useCase("Test", errors = ::toAppError) {
      startWith { command ->
        ensure(command.orderId.isNotBlank()) { OrderError.InvalidOrderId }
        command.orderId
      }.result { it }
    }

    assertEquals(Either.Left(AppError("invalid")), useCase(PlaceOrder("", 1)))
    assertEquals(Either.Right("o-1"), useCase(PlaceOrder("o-1", 1)))
  }

  @Test
  fun `exceptions in startWith and result become Failure Unexpected`() = runTest {
    val badStart: UseCase<PlaceOrder, Failure<Nothing>, String> = useCase("Test") {
      startWith<String> { error("start failed") }.result { it }
    }
    val badResult: UseCase<PlaceOrder, Failure<Nothing>, String> = useCase("Test") {
      startWith { command -> command.orderId }.result { error("projection failed") }
    }

    assertEquals("start failed", assertIs<Failure.Unexpected>(badStart(PlaceOrder("o", 1)).leftOrNull()).cause.message)
    assertEquals("projection failed", assertIs<Failure.Unexpected>(badResult(PlaceOrder("o", 1)).leftOrNull()).cause.message)
  }

  @Test
  fun `result can project from the last emitted event`() = runTest {
    val useCase: UseCase<PlaceOrder, Failure<OrderError>, Int> = useCase("Test") {
      startWith { command -> OrderDraft(command.orderId, command.quantity, command.priority) }
        .then(validateOrder)
        .then(priceOrder)
        .result { requireEvent<OrderPriced>().total }
    }

    assertEquals(Either.Right(300), useCase(PlaceOrder("o-1", 3)))
  }

  @Test
  fun `a missing requireEvent fails as Failure Unexpected`() = runTest {
    val useCase: UseCase<PlaceOrder, Failure<OrderError>, Int> = useCase("Test") {
      startWith { command -> OrderDraft(command.orderId, command.quantity, command.priority) }
        .result { requireEvent<OrderPriced>().total }
    }

    assertIs<Failure.Unexpected>(useCase(PlaceOrder("o-1", 3)).leftOrNull())
  }

  @Test
  fun `error mapper exceptions propagate to the caller`() = runTest {
    val useCase: UseCase<PlaceOrder, AppError, String> = useCase("Test", errors = { _: Failure<OrderError> -> error("bad mapper") }) {
      startWith { command -> OrderDraft(command.orderId, command.quantity, command.priority) }
        .then(validateOrder)
        .result { it.orderId }
    }

    assertFailsWith<IllegalStateException> { useCase(PlaceOrder("o-1", 0)) }
  }

  @Test
  fun `a timeout started inside a step becomes Failure Unexpected`() = runTest {
    val slow = workflow<OrderDraft, OrderDraft, Nothing>("slow") { withTimeout(10.milliseconds) { awaitCancellation() } }
    val useCase: UseCase<PlaceOrder, Failure<OrderError>, String> = useCase("Test") {
      startWith { command -> OrderDraft(command.orderId, command.quantity, command.priority) }
        .then(slow)
        .result { it.orderId }
    }

    assertIs<Failure.Unexpected>(useCase(PlaceOrder("o-1", 1)).leftOrNull())
  }

  @Test
  fun `context flows from the caller through steps into the result`() = runTest {
    val useCase: UseCase<PlaceOrder, Failure<OrderError>, String> = useCase("Test") {
      startWith { command -> OrderDraft(command.orderId, command.quantity, command.priority) }
        .then("tag-tenant") { draft -> "${draft.orderId}@${context[tenant]}" }
        .result { tagged -> "$tagged/${context[tenant]}" }
    }

    assertEquals(Either.Right("o-1@acme/acme"), useCase(PlaceOrder("o-1", 1), WorkflowContext.of(tenant to "acme")))
  }

  @Test
  fun `parallel side effects keep the state and merge events in workflow order`() = runTest {
    val sendReceipt = workflow<PricedOrder, Unit, Nothing>("send-receipt") { emit(ReceiptSent(it.orderId)) }
    val publish = workflow<PricedOrder, String, Nothing>("publish") { emit(OrderPriced(it.total)); "ignored output" }
    val useCase: UseCase<PlaceOrder, Failure<OrderError>, List<String>> = useCase("Test") {
      startWith { command -> PricedOrder(command.orderId, 100) }
        .parallel(sendReceipt, publish)
        .result { order -> events.map { "${it::class.simpleName}:${order.orderId}" } }
    }

    assertEquals(Either.Right(listOf("ReceiptSent:o-1", "OrderPriced:o-1")), useCase(PlaceOrder("o-1", 1)))
  }

  @Test
  fun `parallel side effect failure fails the use case`() = runTest {
    val failing = workflow<PricedOrder, Unit, String>("failing") { raise("down") }
    val useCase: UseCase<PlaceOrder, Failure<String>, String> = useCase("Test") {
      startWith { command -> PricedOrder(command.orderId, 100) }
        .parallel(failing, workflow("noop") { })
        .result { it.orderId }
    }

    assertEquals(Either.Left(Failure.Raised("down")), useCase(PlaceOrder("o-1", 1)))
  }

  @Test
  fun `result can return the full workflow result`() = runTest {
    val detailed: UseCase<PlaceOrder, Failure<OrderError>, WorkflowResult<PricedOrder>> = useCase("Test") {
      startWith { command -> OrderDraft(command.orderId, command.quantity, command.priority) }
        .then(validateOrder)
        .then(priceOrder)
        .result { result }
    }

    val value = detailed(PlaceOrder("o-1", 2)).getOrNull()!!
    assertEquals(PricedOrder("o-1", 200), value.state)
    assertEquals(2, value.events.size)
  }

  @Test
  fun `UseCase can be implemented directly`() = runTest {
    val direct = UseCase<String, Nothing, Int> { input, _ -> Either.Right(input.length) }
    assertEquals(Either.Right(3), direct("abc"))
  }

  @Test
  fun `inline guard and then steps run in the pipeline`() = runTest {
    val checkOrder = useCase<PlaceOrder, OrderError, String>(name = "CheckOrder") {
      startWith { command -> OrderDraft(command.orderId, command.quantity, command.priority) }
        .guard("authorize") { draft -> ensure(draft.quantity < 5) { OrderError.PriceTooHigh(draft.quantity) } }
        .then(validateOrder)
        .then("label") { order -> "${order.orderId}:${order.quantity}" }
        .result { it }
    }

    assertEquals(Either.Right("o-1:2"), checkOrder(PlaceOrder("o-1", 2)))
    assertEquals(Either.Left(Failure.Raised(OrderError.PriceTooHigh(7))), checkOrder(PlaceOrder("o-2", 7)))
  }

  @Test
  fun `startWith returning an Either fails instead of becoming the state`() = runTest {
    val draftOf = { command: PlaceOrder -> Either.Right(OrderDraft(command.orderId, command.quantity, command.priority)) }
    val forgotBind = useCase<PlaceOrder, Nothing, String>(name = "ForgotBind") {
      startWith { command -> draftOf(command) }
        .result { it.toString() }
    }

    val failure = assertIs<Either.Left<Failure<Nothing>>>(forgotBind(PlaceOrder("o-1", 1))).value
    assertIs<Failure.Unexpected>(failure)
  }
}
