package io.liquidsoftware.workflow.recipes.micrometer

import arrow.core.raise.ensure
import io.liquidsoftware.workflow.Failure
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.UseCaseConfig
import io.liquidsoftware.workflow.UseCaseSummary
import io.liquidsoftware.workflow.useCase
import io.liquidsoftware.workflow.useCaseConfig
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.toJavaDuration

// Recipe: docs/recipes/micrometer.md

class UseCaseMetrics(private val registry: MeterRegistry) {
  fun record(summary: UseCaseSummary) {
    Timer.builder("usecase")
      .tag("usecase", summary.useCaseName)
      .tag("outcome", if (summary.succeeded) "success" else "failure")
      .tag("error", summary.rootError?.type ?: "none")
      .tag("step", summary.rootError?.workflowId ?: "none")
      .register(registry)
      .record(summary.duration.toJavaDuration())

    summary.executions.forEach { execution ->
      Timer.builder("usecase.step")
        .tag("usecase", summary.useCaseName)
        .tag("step", execution.workflowId)
        .tag("outcome", if (execution.succeeded) "success" else "failure")
        .register(registry)
        .record(execution.duration.toJavaDuration())
    }

    if (summary.launchedFailures.isNotEmpty()) {
      registry.counter("usecase.launched.failures", "usecase", summary.useCaseName)
        .increment(summary.launchedFailures.size.toDouble())
    }
  }
}

fun appConfig(registry: MeterRegistry): UseCaseConfig = useCaseConfig {
  onSummary(UseCaseMetrics(registry)::record)
}

data class PlaceOrder(val customerId: String, val total: Long)
data class OrderConfirmation(val orderId: String)

sealed interface OrderError {
  data object LimitExceeded : OrderError
}

fun placeOrder(config: UseCaseConfig): UseCase<PlaceOrder, Failure<OrderError>, OrderConfirmation> =
  useCase("PlaceOrder", config = config) {
    startWith { command -> command }
      .guard("check-limit") { command -> ensure(command.total <= 50_000) { OrderError.LimitExceeded } }
      .then("create-order") { command -> OrderConfirmation("o-${command.customerId}") }
      .result { it }
  }

class MicrometerRecipeTest {
  @Test
  fun `records a timer per use case outcome and per step`() = runTest {
    val registry = SimpleMeterRegistry()
    val useCase = placeOrder(appConfig(registry))

    useCase(PlaceOrder("c-1", 1_000))
    useCase(PlaceOrder("c-2", 2_000))
    useCase(PlaceOrder("c-3", 99_000))

    val succeeded = registry.get("usecase").tags("usecase", "PlaceOrder", "outcome", "success").timer()
    val failed = registry.get("usecase").tags("outcome", "failure", "error", "LimitExceeded", "step", "check-limit").timer()
    assertEquals(2, succeeded.count())
    assertEquals(1, failed.count())
    assertEquals(3, registry.get("usecase.step").tags("step", "check-limit").timers().sumOf { it.count() })
    assertEquals(2, registry.get("usecase.step").tags("step", "create-order").timer().count())
  }
}
