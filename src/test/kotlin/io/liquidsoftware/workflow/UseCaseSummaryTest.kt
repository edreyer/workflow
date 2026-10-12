package io.liquidsoftware.workflow

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UseCaseSummaryTest {

  data class Input(val value: Int)
  data class Done(val value: Int) : BaseEvent()

  private val summaries = mutableListOf<UseCaseSummary>()

  @BeforeTest
  fun registerListener() {
    resetUseCaseLoggingGlobalConfigForTest()
    useCaseLoggingGlobalConfig { onSummary { summaries += it } }
  }

  @AfterTest
  fun reset() = resetUseCaseLoggingGlobalConfigForTest()

  private val step = workflow<Input, Input>("step") { emit(Done(it.value)); it }
  private val rejectNegative = workflow<Input, Input>("reject-negative") { input ->
    if (input.value < 0) raise(WorkflowError.DomainError("negative", "value ${input.value} is negative"))
    input
  }

  private val useCase: UseCase<Input, WorkflowError, Int> = useCase(name = "Summarized") {
    startWith { it }
      .then(step)
      .then(rejectNegative)
      .result { it.value }
  }

  @Test
  fun `success summary carries executions, events and correlation id`() = runTest {
    useCase(Input(1), WorkflowContext.of(WorkflowContext.CORRELATION_ID to "corr-1"))

    val summary = summaries.single()
    assertTrue(summary.succeeded)
    assertEquals("Summarized", summary.useCaseName)
    assertEquals(1, summary.eventsCount)
    assertEquals(listOf("step", "reject-negative"), summary.executions.map { it.workflowId })
    assertEquals("corr-1", summary.correlationId)
    assertNull(summary.rootError)
  }

  @Test
  fun `failure summary names the failing step and redacts the message by default`() = runTest {
    useCase(Input(-5), WorkflowContext.of(WorkflowContext.CORRELATION_ID to "corr-2"))

    val summary = summaries.single()
    assertEquals(false, summary.succeeded)
    assertEquals(RootErrorInfo("DomainError", "negative", null, "reject-negative"), summary.rootError)
    assertEquals("corr-2", summary.correlationId)
    assertTrue("-5" !in summary.toString())
    assertEquals("value -5 is negative", summary.withErrorMessages().rootError?.message)
  }

  @Test
  fun `failure summary keeps the run's events and launched failures and ignores launched steps when naming the failure`() = runTest {
    val failingSideEffect = workflow<Input, Unit>("audit") { raise(WorkflowError.ExecutionError("audit down")) }
    val failing: UseCase<Input, WorkflowError, Int> = useCase(name = "Failing") {
      startWith { it }
        .then(step)
        .thenLaunch(failingSideEffect)
        .awaitLaunched()
        .then(rejectNegative)
        .result { it.value }
    }

    failing(Input(-1))

    val summary = summaries.single()
    assertEquals("reject-negative", summary.rootError?.workflowId)
    assertEquals(2, summary.eventsCount) // Done + LaunchedFailureEvent
    assertEquals(1, summary.launchedFailures.size)
  }

  @Test
  fun `failure summary names the parallel branch whose error was returned`() = runTest {
    val slowFailure = workflow<Input, Int>("slow-failure") {
      delay(50)
      raise(WorkflowError.DomainError("slow", "slow failed"))
    }
    val fastFailure = workflow<Input, Int>("fast-failure") { raise(WorkflowError.DomainError("fast", "fast failed")) }
    val parallelFailures: UseCase<Input, WorkflowError, Int> = useCase(name = "ParallelFailures") {
      startWith { it }
        .parallelJoin(slowFailure, fastFailure) { a, b -> a + b }
        .result { it }
    }

    parallelFailures(Input(1))

    assertEquals(RootErrorInfo("DomainError", "slow", null, "slow-failure"), summaries.single().rootError)
  }

  @Test
  fun `failure outside any step names no step`() = runTest {
    val rejecting: UseCase<Input, WorkflowError, Int> = useCase(name = "Rejecting") {
      startWith<Input> { raise(WorkflowError.ValidationError("bad")) }
        .then(step)
        .result { it.value }
    }

    rejecting(Input(1))

    assertNull(summaries.single().rootError?.workflowId)
  }

  @Test
  fun `clearSummaryListeners removes registered listeners`() = runTest {
    useCaseLoggingGlobalConfig { clearSummaryListeners() }

    useCase(Input(1))

    assertTrue(summaries.isEmpty())
  }

  @Test
  fun `a throwing listener does not affect the use case`() = runTest {
    useCaseLoggingGlobalConfig { onSummary { error("listener down") } }

    assertEquals(3, useCase(Input(3)).getOrNull())
    assertEquals(1, summaries.size)
  }
}
