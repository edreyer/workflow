package io.liquidsoftware.workflow.tutorial.ch13

import arrow.core.raise.ensure
import io.liquidsoftware.workflow.DefaultSummaryFields
import io.liquidsoftware.workflow.Failure
import io.liquidsoftware.workflow.OutcomeStyle
import io.liquidsoftware.workflow.RootErrorInfo
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.UseCaseConfig
import io.liquidsoftware.workflow.UseCaseSummary
import io.liquidsoftware.workflow.useCase
import io.liquidsoftware.workflow.useCaseConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Tutorial chapter 13: docs/tutorial/13-observability.md

data class PlaceOrder(val customerId: String, val total: Long)
data class OrderConfirmation(val orderId: String, val total: Long)

sealed interface OrderError {
  data class LimitExceeded(val customerId: String, val limit: Long) : OrderError
}

/** Stands in for a metrics client. */
class Metrics {
  val recorded = mutableListOf<String>()
  fun record(summary: UseCaseSummary) {
    recorded += "${summary.useCaseName} ${if (summary.succeeded) "ok" else "failed at ${summary.rootError?.workflowId}"}"
  }
}

// One config for the whole application, built at startup and passed to every use case.
fun appConfig(metrics: Metrics): UseCaseConfig = useCaseConfig {
  outcomeStyle(OutcomeStyle.Plain)
  defaultSummaryFields(DefaultSummaryFields.Enabled)
  onSummary { summary -> metrics.record(summary) }
}

fun placeOrder(config: UseCaseConfig): UseCase<PlaceOrder, Failure<OrderError>, OrderConfirmation> =
  useCase("PlaceOrder", config = config) {
    startWith { command -> command }
      .guard("check-limit") { command ->
        ensure(command.total <= 50_000) { OrderError.LimitExceeded(command.customerId, 50_000) }
      }
      .then("create-order") { command -> OrderConfirmation("o-${command.customerId}", command.total) }
      .result { it }
      // Extra fields for the debug finish log. Only evaluated when debug logging is on.
      .logging {
        success { (confirmation) -> field("orderId", confirmation.orderId) }
        failure { failure -> field("unexpected", failure is Failure.Unexpected) }
      }
  }

class Ch13ObservabilityTest {
  @Test
  fun `every invocation produces a summary`() = runTest {
    val metrics = Metrics()
    val useCase = placeOrder(appConfig(metrics))

    useCase(PlaceOrder("c-1", 1_000))
    useCase(PlaceOrder("c-1", 99_000))

    assertEquals(listOf("PlaceOrder ok", "PlaceOrder failed at check-limit"), metrics.recorded)
  }

  @Test
  fun `error messages are redacted unless asked for`() = runTest {
    val summaries = mutableListOf<UseCaseSummary>()
    placeOrder(useCaseConfig { onSummary { summaries += it } })(PlaceOrder("c-1", 99_000))
    val summary = summaries.single()

    assertEquals(listOf("check-limit"), summary.executions.map { it.workflowId })
    assertNull(summary.rootError?.message) // the error holds a customer id, so it's redacted
    assertEquals(
      RootErrorInfo(
        type = "LimitExceeded",
        unexpected = false,
        message = "LimitExceeded(customerId=c-1, limit=50000)",
        workflowId = "check-limit",
      ),
      summary.withErrorMessages().rootError,
    )
  }

  @Test
  fun `a use case can adjust the application config`() = runTest {
    val metrics = Metrics()
    val extra = mutableListOf<String>()
    val config = useCaseConfig(appConfig(metrics)) { onSummary { extra += it.useCaseName } }

    placeOrder(config)(PlaceOrder("c-1", 1_000))

    assertEquals(listOf("PlaceOrder ok"), metrics.recorded)
    assertEquals(listOf("PlaceOrder"), extra)
  }
}
