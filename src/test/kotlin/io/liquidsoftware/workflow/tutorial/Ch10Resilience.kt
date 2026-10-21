package io.liquidsoftware.workflow.tutorial.ch10

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import arrow.resilience.Schedule
import io.liquidsoftware.workflow.Failure
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.Workflow
import io.liquidsoftware.workflow.recover
import io.liquidsoftware.workflow.retry
import io.liquidsoftware.workflow.timeout
import io.liquidsoftware.workflow.useCase
import io.liquidsoftware.workflow.workflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Tutorial chapter 10: docs/tutorial/10-resilience.md

data class Order(val id: String, val total: Long)
data class PaidOrder(val order: Order, val payment: Payment)

sealed interface Payment {
  data class Charged(val chargeId: String) : Payment
  data object PendingReview : Payment
}

sealed interface PaymentError {
  data object Declined : PaymentError
  data object Unavailable : PaymentError
  data object TimedOut : PaymentError
}

/** A gateway that answers each call with the next scripted response. */
class PaymentGateway(private vararg val responses: Response) {
  sealed interface Response
  data object Ok : Response
  data object Decline : Response
  data object Busy : Response // the gateway says to try again
  data object Crash : Response // the client throws
  data class Slow(val by: Duration) : Response

  var calls = 0
    private set

  context(_: Raise<PaymentError>)
  suspend fun charge(order: Order): String {
    when (val response = responses.getOrElse(calls++) { Ok }) {
      Ok -> Unit
      Decline -> raise(PaymentError.Declined)
      Busy -> raise(PaymentError.Unavailable)
      Crash -> error("connection reset")
      is Slow -> delay(response.by)
    }
    return "ch-${order.id}-$calls"
  }
}

// Retry exceptions and transient errors, at most twice, backing off from 200 ms. A decline is a decision: don't retry it.
val transient: Schedule<Failure<PaymentError>, *> =
  Schedule.exponential<Failure<PaymentError>>(200.milliseconds)
    .and(Schedule.recurs(2))
    .doWhile { failure, _ ->
      failure is Failure.Unexpected || failure == Failure.Raised(PaymentError.Unavailable) ||
        failure == Failure.Raised(PaymentError.TimedOut)
    }

fun chargeCard(gateway: PaymentGateway): Workflow<Order, PaidOrder, PaymentError> =
  workflow<Order, PaidOrder, PaymentError>("charge-card") { order ->
    PaidOrder(order, Payment.Charged(gateway.charge(order)))
  }
    .timeout(2.seconds) { PaymentError.TimedOut } // each attempt gets 2 seconds
    .retry(transient)
    // Large declined orders go to manual review instead of failing. Other errors stay errors.
    .recover { order, error ->
      if (error == PaymentError.Declined && order.total >= 10_000) PaidOrder(order, Payment.PendingReview) else raise(error)
    }

fun placeOrder(gateway: PaymentGateway): UseCase<Order, Failure<PaymentError>, Payment> =
  useCase("PlaceOrder") {
    startWith { order -> order }
      .then(chargeCard(gateway))
      .result { paid -> paid.payment }
  }

class Ch10ResilienceTest {
  private val order = Order("o-1", 2_500)

  @Test
  fun `transient failures are retried`() = runTest {
    val gateway = PaymentGateway(PaymentGateway.Busy, PaymentGateway.Crash)

    assertEquals(Either.Right(Payment.Charged("ch-o-1-3")), placeOrder(gateway)(order))
    assertEquals(3, gateway.calls)
  }

  @Test
  fun `a slow attempt times out with a typed error and is retried`() = runTest {
    val gateway = PaymentGateway(PaymentGateway.Slow(5.seconds))

    assertEquals(Either.Right(Payment.Charged("ch-o-1-2")), placeOrder(gateway)(order))
  }

  @Test
  fun `the step fails with the last failure when the schedule stops`() = runTest {
    val gateway = PaymentGateway(PaymentGateway.Busy, PaymentGateway.Busy, PaymentGateway.Busy)

    assertEquals(Either.Left(Failure.Raised(PaymentError.Unavailable)), placeOrder(gateway)(order))
    assertEquals(3, gateway.calls)
  }

  @Test
  fun `a decline is not retried, and recover decides what it means`() = runTest {
    assertEquals(Either.Left(Failure.Raised(PaymentError.Declined)), placeOrder(PaymentGateway(PaymentGateway.Decline))(order))
    assertEquals(
      Either.Right(Payment.PendingReview),
      placeOrder(PaymentGateway(PaymentGateway.Decline))(Order("o-2", 50_000)),
    )
  }
}
