package io.liquidsoftware.workflow.tutorial.ch09

import arrow.core.Either
import io.liquidsoftware.workflow.Event
import io.liquidsoftware.workflow.Failure
import io.liquidsoftware.workflow.LaunchedFailureEvent
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.UseCaseConfig
import io.liquidsoftware.workflow.UseCaseSummary
import io.liquidsoftware.workflow.Workflow
import io.liquidsoftware.workflow.useCase
import io.liquidsoftware.workflow.useCaseConfig
import io.liquidsoftware.workflow.workflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

// Tutorial chapter 9: docs/tutorial/09-launched-work.md

data class Order(val id: String, val email: String)
data class OrderConfirmation(val orderId: String, val emailed: Boolean)

data class ConfirmationEmailed(val orderId: String) : Event
data class AuditRecorded(val orderId: String) : Event

class Mailer(private val latencySeconds: Int = 1, private val down: Boolean = false) {
  suspend fun send(to: String) {
    delay(latencySeconds.seconds)
    check(!down) { "mail server unavailable" }
  }
}

fun sendConfirmation(mailer: Mailer): Workflow<Order, Unit, Nothing> =
  workflow("send-confirmation") { order ->
    mailer.send(order.email)
    emit(ConfirmationEmailed(order.id))
  }

val recordAudit: Workflow<Order, Unit, Nothing> =
  workflow("record-audit") { order -> delay(3.seconds); emit(AuditRecorded(order.id)) }

fun placeOrder(mailer: Mailer, config: UseCaseConfig = UseCaseConfig.Default): UseCase<Order, Failure<Nothing>, OrderConfirmation> =
  useCase("PlaceOrder", config = config) {
    startWith { order -> order }
      // Later steps don't wait for launched work.
      .thenLaunch(sendConfirmation(mailer), timeout = 2.seconds)
      .then("save-order") { order -> delay(200); order }
      // Wait here when a later step or the result needs the launched work's events.
      .awaitLaunched()
      .result { order -> OrderConfirmation(order.id, emailed = events.any { it is ConfirmationEmailed }) }
  }

class Ch09LaunchedWorkTest {
  private val order = Order("o-1", "person@example.com")

  @Test
  fun `launched work runs alongside later steps`() = runTest {
    assertEquals(Either.Right(OrderConfirmation("o-1", emailed = true)), placeOrder(Mailer())(order))
    assertEquals(1_000, currentTime) // the 200 ms save ran during the 1 s send
  }

  @Test
  fun `a launched failure never fails the use case`() = runTest {
    val summaries = mutableListOf<UseCaseSummary>()
    val config = useCaseConfig { onSummary { summaries += it } }

    assertEquals(Either.Right(OrderConfirmation("o-1", emailed = false)), placeOrder(Mailer(down = true), config)(order))
    assertEquals(listOf("thenLaunch failure send-confirmation (An exception occurred)"), summaries.single().launchedFailures)
  }

  @Test
  fun `a launched timeout is reported as a launched failure`() = runTest {
    val result = useCase<Order, Nothing, List<Event>>("PlaceOrder") {
      startWith { order -> order }
        .thenLaunch(sendConfirmation(Mailer(latencySeconds = 5)), timeout = 2.seconds)
        .result { events }
    }(order)

    assertEquals(
      Either.Right(listOf<Event>(LaunchedFailureEvent("send-confirmation", "thenLaunch timeout: send-confirmation"))),
      result,
    )
  }

  @Test
  fun `launchTimeout in the config bounds launched work that passes no timeout`() = runTest {
    val config = useCaseConfig { launchTimeout(1.seconds) }
    val audited = useCase<Order, Nothing, Int>("PlaceOrder", config = config) {
      startWith { order -> order }
        .thenLaunch(recordAudit)
        .result { events.filterIsInstance<LaunchedFailureEvent>().size }
    }

    assertEquals(Either.Right(1), audited(order))
    assertEquals(1_000, currentTime)
  }
}
