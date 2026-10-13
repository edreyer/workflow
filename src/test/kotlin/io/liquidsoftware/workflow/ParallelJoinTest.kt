package io.liquidsoftware.workflow

import arrow.core.Either
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParallelJoinTest {

  data class Order(val id: String, val amount: Int)
  data class Subtotal(val amount: Int)
  data class Tax(val amount: Int)
  data class Priced(val id: String, val subtotal: Int, val tax: Int)
  data class Computed(val name: String) : BaseEvent()

  private val subtotal = workflow<Order, Subtotal, Nothing>("subtotal") { order ->
    delay(20)
    emit(Computed("subtotal"))
    Subtotal(order.amount)
  }

  private val tax = workflow<Order, Tax, Nothing>("tax") { order ->
    emit(Computed("tax"))
    Tax(order.amount / 10)
  }

  @Test
  fun `merges branch outputs and keeps events in parameter order`() = runTest {
    val join = parallelJoin(subtotal, tax) { s, t -> Priced("o-1", s.amount, t.amount) }

    val result = assertIs<Either.Right<WorkflowResult<Priced>>>(join(Order("o-1", 100))).value

    assertEquals(Priced("o-1", 100, 10), result.state)
    assertEquals(listOf("subtotal", "tax"), result.events.filterIsInstance<Computed>().map { it.name })
  }

  @Test
  fun `WaitAll returns the first error by parameter order`() = runTest {
    val slowFailure = workflow<Order, Subtotal, String>("slow-failure") {
      delay(50)
      raise("first")
    }
    val fastFailure = workflow<Order, Tax, String>("fast-failure") { raise("second") }

    val result = parallelJoin(slowFailure, fastFailure) { _, _ -> Unit }(Order("o", 1))

    assertEquals(Either.Left(Failure.Raised("first")), result)
  }

  @Test
  fun `FailFast cancels the remaining branches`() = runTest {
    var slowFinished = false
    val slow = workflow<Order, Subtotal, Nothing>("slow") {
      try {
        awaitCancellation()
      } finally {
        slowFinished = true
      }
    }
    val failing = workflow<Order, Tax, String>("failing") { raise("boom") }

    val result = parallelJoin(slow, failing, ParallelErrorPolicy.FailFast) { _, _ -> Unit }(Order("o", 1))

    assertEquals(Either.Left(Failure.Raised("boom")), result)
    assertTrue(slowFinished)
  }

  @Test
  fun `parallelJoin in a pipeline is type checked against the current state`() = runTest {
    val useCase: UseCase<Order, Failure<Nothing>, Int> = useCase("Test") {
      startWith { it }
        .parallelJoin(subtotal, tax) { s, t -> s.amount + t.amount }
        .then(workflow<Int, Int, Nothing>("double") { it * 2 })
        .result { it }
    }

    assertEquals(Either.Right(220), useCase(Order("o-1", 100)))
  }

  @Test
  fun `supports eight branches`() = runTest {
    fun n(value: Int) = workflow<Int, Int, Nothing>("n$value") { it + value }
    val join = parallelJoin(n(1), n(2), n(3), n(4), n(5), n(6), n(7), n(8)) { a, b, c, d, e, f, g, h ->
      listOf(a, b, c, d, e, f, g, h)
    }

    assertEquals((11..18).toList(), join(10).getOrNull()?.state)
  }
}
