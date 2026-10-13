package io.liquidsoftware.workflow

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UseCaseSummaryTest {

  data class Input(val value: Int)
  data class Done(val value: Int) : BaseEvent()

  private val summaries = mutableListOf<UseCaseSummary>()

  private val config = useCaseConfig { onSummary { summaries += it } }

  sealed interface TestError {
    data class Negative(val value: Int) : TestError
    data class Down(val reason: String) : TestError
    data object Shared : TestError
  }

  private val step = workflow<Input, Input, Nothing>("step") { emit(Done(it.value)); it }
  private val rejectNegative = workflow<Input, Input, TestError>("reject-negative") { input ->
    if (input.value < 0) raise(TestError.Negative(input.value))
    input
  }

  private val useCase: UseCase<Input, Failure<TestError>, Int> = useCase(name = "Summarized", config = config) {
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
    assertEquals(RootErrorInfo("Negative", false, null, "reject-negative"), summary.rootError)
    assertEquals("corr-2", summary.correlationId)
    assertTrue("-5" !in summary.toString())
    assertEquals("Negative(value=-5)", summary.withErrorMessages().rootError?.message)
  }

  @Test
  fun `failure summary keeps the run's events and launched failures and ignores launched steps when naming the failure`() = runTest {
    val failingSideEffect = workflow<Input, Unit, TestError>("audit") { raise(TestError.Down("audit")) }
    val failing: UseCase<Input, Failure<TestError>, Int> = useCase(name = "Failing", config = config) {
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
    val slowFailure = workflow<Input, Int, TestError>("slow-failure") {
      delay(50)
      raise(TestError.Down("slow"))
    }
    val fastFailure = workflow<Input, Int, TestError>("fast-failure") { raise(TestError.Down("fast")) }
    val parallelFailures: UseCase<Input, Failure<TestError>, Int> = useCase(name = "ParallelFailures", config = config) {
      startWith { it }
        .parallelJoin(slowFailure, fastFailure) { a, b -> a + b }
        .result { it }
    }

    parallelFailures(Input(1))

    assertEquals(RootErrorInfo("Down", false, null, "slow-failure"), summaries.single().rootError)
  }

  @Test
  fun `failure summary names the selected branch when branches raise the same error instance`() = runTest {
    val slow = workflow<Input, Int, TestError>("slow-shared") {
      delay(50)
      raise(TestError.Shared)
    }
    val fast = workflow<Input, Int, TestError>("fast-shared") { raise(TestError.Shared) }
    val sharedFailures: UseCase<Input, Failure<TestError>, Int> = useCase(name = "SharedFailures", config = config) {
      startWith { it }
        .parallelJoin(slow, fast) { a, b -> a + b }
        .result { it }
    }

    sharedFailures(Input(1))

    assertEquals("slow-shared", summaries.single().rootError?.workflowId)
  }

  @Test
  fun `failure summary keeps events from parallel branches that succeeded`() = runTest {
    val emitting = workflow<Input, Unit, TestError>("emitting") { emit(Done(it.value)) }
    val failing = workflow<Input, Unit, TestError>("failing") {
      delay(10)
      raise(TestError.Down("down"))
    }
    val partial: UseCase<Input, Failure<TestError>, Int> = useCase(name = "Partial", config = config) {
      startWith { it }
        .parallel(emitting, failing)
        .result { it.value }
    }

    partial(Input(1))

    assertEquals(1, summaries.single().eventsCount)
    assertEquals("failing", summaries.single().rootError?.workflowId)
  }

  @Test
  fun `failure summary keeps events from parallelJoin branches that succeeded`() = runTest {
    val emitting = workflow<Input, Int, TestError>("emitting-join") { emit(Done(it.value)); 1 }
    val failing = workflow<Input, Int, TestError>("failing-join") {
      delay(10)
      raise(TestError.Down("down"))
    }
    val partial: UseCase<Input, Failure<TestError>, Int> = useCase(name = "PartialJoin", config = config) {
      startWith { it }
        .parallelJoin(emitting, failing) { a, b -> a + b }
        .result { it }
    }

    partial(Input(1))

    assertEquals(1, summaries.single().eventsCount)
  }

  @Test
  fun `listeners still get a summary when the error mapper throws`() = runTest {
    val throwingMapper: UseCase<Input, String, Int> = useCase(name = "ThrowingMapper", errors = { _: Failure<TestError> -> error("bad mapper") }, config = config) {
      startWith { it }.then(step).then(rejectNegative).result { it.value }
    }

    runCatching { throwingMapper(Input(-1)) }

    assertEquals(1, summaries.single().eventsCount)
  }

  @Test
  fun `failure outside any step names no step`() = runTest {
    val rejecting: UseCase<Input, Failure<TestError>, Int> = useCase(name = "Rejecting", config = config) {
      startWith<Input> { raise(TestError.Down("bad")) }
        .then(step)
        .result { it.value }
    }

    rejecting(Input(1))

    assertNull(summaries.single().rootError?.workflowId)
  }

  @Test
  fun `use cases without a config notify no listeners`() = runTest {
    val unconfigured: UseCase<Input, Failure<TestError>, Int> = useCase(name = "Unconfigured") { startWith { it }.result { it.value } }

    unconfigured(Input(1))

    assertTrue(summaries.isEmpty())
  }

  @Test
  fun `a throwing listener does not affect the use case`() = runTest {
    val withThrowingListener = useCase<Input, Nothing, Int>(name = "Summarized", config = useCaseConfig(config) { onSummary { error("listener down") } }) {
      startWith { input -> input }.result { it.value }
    }

    assertEquals(3, withThrowingListener(Input(3)).getOrNull())
    assertEquals(1, summaries.size)
  }
}
