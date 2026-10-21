package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.resilience.Schedule
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ResilienceTest {

  data class Charged(val attempt: Int) : BaseEvent()

  sealed interface PaymentError {
    data object TimedOut : PaymentError
    data object Unavailable : PaymentError
    data object Declined : PaymentError
  }

  sealed interface OrderError {
    data class Payment(val error: PaymentError) : OrderError
  }

  private val summaries = mutableListOf<UseCaseSummary>()
  private val config = useCaseConfig { onSummary { summaries += it } }

  /** A step that fails with [failures] in order, then returns the attempt number. */
  private fun flaky(vararg failures: () -> PaymentError): Pair<Workflow<String, Int, PaymentError>, () -> Int> {
    var attempts = 0
    val step = workflow<String, Int, PaymentError>("charge") {
      attempts++
      emit(Charged(attempts))
      failures.getOrNull(attempts - 1)?.let { raise(it()) }
      attempts
    }
    return step to { attempts }
  }

  private fun <O> useCaseOf(step: Workflow<String, O, PaymentError>): UseCase<String, Failure<PaymentError>, Pair<O, List<Event>>> =
    useCase("Pay", config = config) {
      startWith { it }
        .then(step)
        .result { it to events }
    }

  @Test
  fun `timeout raises the typed error and is attributed to the step`() = runTest {
    val slow = workflow<String, String, PaymentError>("charge") { delay(5.seconds); "paid" }
      .timeout(1.seconds) { PaymentError.TimedOut }
    val fast = workflow<String, String?, PaymentError>("charge") { null }
      .timeout(1.seconds) { PaymentError.TimedOut }

    assertEquals(Either.Left(Failure.Raised(PaymentError.TimedOut)), useCaseOf(slow)("o-1"))
    assertEquals("charge", summaries.last().rootError?.workflowId)
    assertEquals(Either.Right(null to emptyList()), useCaseOf(fast)("o-1"))
  }

  @Test
  fun `retry keeps only the successful attempt's events and records the step once`() = runTest {
    val (step, attempts) = flaky({ PaymentError.Unavailable }, { PaymentError.Unavailable })

    val result = useCaseOf(step.retry(Schedule.recurs(2)))("o-1")

    assertEquals(Either.Right(3 to listOf<Event>(Charged(3))), result)
    assertEquals(3, attempts())
    assertEquals(listOf("charge"), summaries.single().executions.map { it.workflowId })
  }

  @Test
  fun `retry fails with the last failure when the schedule stops`() = runTest {
    val (step, attempts) = flaky({ PaymentError.Unavailable }, { PaymentError.TimedOut }, { PaymentError.Unavailable })

    assertEquals(Either.Left(Failure.Raised(PaymentError.TimedOut)), useCaseOf(step.retry(Schedule.recurs(1)))("o-1"))
    assertEquals(2, attempts())
    assertEquals("charge", summaries.last().rootError?.workflowId)
  }

  @Test
  fun `retry stops on errors the schedule does not accept`() = runTest {
    val (step, attempts) = flaky({ PaymentError.Declined })
    val transient = Schedule.recurs<Failure<PaymentError>>(3)
      .doWhile { failure, _ -> failure is Failure.Unexpected || failure == Failure.Raised(PaymentError.Unavailable) }

    assertEquals(Either.Left(Failure.Raised(PaymentError.Declined)), useCaseOf(step.retry(transient))("o-1"))
    assertEquals(1, attempts())
  }

  @Test
  fun `mapError around a retry maps its last error`() = runTest {
    val (step, attempts) = flaky({ PaymentError.Unavailable }, { PaymentError.Declined })
    val mapped = step.retry(Schedule.recurs(1)).mapError { OrderError.Payment(it) }

    assertEquals(Either.Left(Failure.Raised(OrderError.Payment(PaymentError.Declined))), mapped("o-1").map { it.state })
    assertEquals(2, attempts())
  }

  @Test
  fun `retry sees thrown exceptions as Failure Unexpected`() = runTest {
    var attempts = 0
    val step = workflow<String, Int, PaymentError>("charge") {
      if (++attempts == 1) error("connection reset")
      attempts
    }
    val seen = mutableListOf<Failure<PaymentError>>()
    val schedule = Schedule.recurs<Failure<PaymentError>>(1).doWhile { failure, _ -> seen += failure; true }

    assertEquals(Either.Right(2), step.retry(schedule)("o-1").map { it.state })
    assertEquals("connection reset", (seen.single() as Failure.Unexpected).cause.message)
  }

  @Test
  fun `timeout per attempt or for all attempts depends on the order`() = runTest {
    var attempts = 0
    val slowOnce = workflow<String, Int, PaymentError>("charge") {
      if (++attempts == 1) delay(5.seconds)
      attempts
    }
    val perAttempt = slowOnce.timeout(1.seconds) { PaymentError.TimedOut }.retry(Schedule.recurs(1))
    assertEquals(Either.Right(2), perAttempt("o-1").map { it.state })

    val alwaysFails = workflow<String, Int, PaymentError>("charge") { raise(PaymentError.Unavailable) }
    val overall = alwaysFails.retry(Schedule.spaced(1.seconds)).timeout(2500.milliseconds) { PaymentError.TimedOut }
    assertEquals(Either.Left(Failure.Raised(PaymentError.TimedOut)), overall("o-1").map { it.state })
  }

  @Test
  fun `recover turns raised errors into output or a new error type`() = runTest {
    val charge = workflow<String, String, PaymentError>("charge") { id ->
      emit(Charged(1))
      when (id) {
        "declined" -> raise(PaymentError.Declined)
        "down" -> raise(PaymentError.Unavailable)
        else -> "paid"
      }
    }
    val recovered = charge.recover<String, String, PaymentError, OrderError> { _, error ->
      if (error is PaymentError.Declined) "manual-review" else raise(OrderError.Payment(error))
    }

    assertEquals(Either.Right("paid" to listOf<Event>(Charged(1))), recovered("ok").map { it.state to it.events })
    assertEquals(Either.Right("manual-review" to emptyList()), recovered("declined").map { it.state to it.events })
    assertEquals(Either.Left(Failure.Raised(OrderError.Payment(PaymentError.Unavailable))), recovered("down").map { it.state })
  }

  @Test
  fun `recover does not handle thrown exceptions`() = runTest {
    val broken = workflow<String, String, PaymentError>("charge") { error("bug") }
      .recover<String, String, PaymentError, PaymentError> { _, _ -> "fallback" }

    assertIs<Failure.Unexpected>(broken("o-1").leftOrNull())
  }

  @Test
  fun `the documented chain compiles and runs`() = runTest {
    val transient = Schedule.exponential<Failure<PaymentError>>(200.milliseconds)
      .and(Schedule.recurs(2))
      .doWhile { failure, _ -> failure is Failure.Unexpected || failure == Failure.Raised(PaymentError.TimedOut) }

    val chargeCard = workflow<String, String, PaymentError>("charge-card") { id ->
      if (id == "declined") raise(PaymentError.Declined) else "paid"
    }
      .timeout(2.seconds) { PaymentError.TimedOut }
      .retry(transient)
      .recover { order, error -> if (error is PaymentError.Declined) "review-$order" else raise(error) }

    assertEquals(Either.Right("review-declined"), chargeCard("declined").map { it.state })
  }
}
