package io.liquidsoftware.workflow

import arrow.core.Either
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ThenLaunchTest {

  data class Seed(val value: String)
  data class SideEffect(val label: String) : BaseEvent()

  private fun sideEffect(id: String, label: String = id, delayMs: Long = 0) =
    workflow<Seed, Unit, Nothing>(id) {
      if (delayMs > 0) delay(delayMs)
      emit(SideEffect(label))
    }

  data class LaunchError(val id: String)

  private fun failing(id: String) = workflow<Seed, Unit, LaunchError>(id) { raise(LaunchError(id)) }

  private fun labels(events: List<Event>) = events.filterIsInstance<SideEffect>().map { it.label }

  @Test
  fun `launched work is merged at awaitLaunched`() = runTest {
    val useCase: UseCase<Seed, Failure<LaunchError>, List<String>> = useCase("Test") {
      startWith { it }
        .thenLaunch(sideEffect("a"))
        .thenLaunch(sideEffect("b"))
        .awaitLaunched()
        .result { labels(events).sorted() }
    }

    assertEquals(Either.Right(listOf("a", "b")), useCase(Seed("x")))
  }

  @Test
  fun `launched work is merged when the pipeline finishes without awaitLaunched`() = runTest {
    val useCase: UseCase<Seed, Failure<LaunchError>, List<String>> = useCase("Test") {
      startWith { it }
        .thenLaunch(sideEffect("late", delayMs = 50))
        .result { labels(events) }
    }

    assertEquals(Either.Right(listOf("late")), useCase(Seed("x")))
  }

  @Test
  fun `launched work does not block later steps`() = runTest {
    val release = CompletableDeferred<Unit>()
    val blocked = workflow<Seed, Unit, Nothing>("blocked") {
      release.await()
      emit(SideEffect("released"))
    }
    val releaser = workflow<Seed, Seed, Nothing>("releaser") {
      release.complete(Unit)
      emit(SideEffect("releaser"))
      it
    }
    val useCase: UseCase<Seed, Failure<LaunchError>, List<String>> = useCase("Test") {
      startWith { it }
        .thenLaunch(blocked)
        .then(releaser)
        .awaitLaunched()
        .result { labels(events).sorted() }
    }

    assertEquals(Either.Right(listOf("released", "releaser")), useCase(Seed("x")))
  }

  @Test
  fun `failures and timeouts are reported without failing the use case`() = runTest {
    val useCase: UseCase<Seed, Failure<LaunchError>, Pair<List<Pair<String, String>>, List<String>>> = useCase("Test") {
      startWith { it }
        .thenLaunch(sideEffect("ok"))
        .thenLaunch(failing("audit"))
        .thenLaunch(sideEffect("slow", delayMs = 200), timeout = 50.milliseconds)
        .awaitLaunched()
        .result {
          events.filterIsInstance<LaunchedFailureEvent>().map { it.workflowId to it.message } to
            context[WorkflowContext.LAUNCHED_FAILURES].orEmpty()
        }
    }

    val (failureEvents, failureMessages) = useCase(Seed("x")).getOrNull()!!

    assertEquals(listOf("audit" to "LaunchError", "slow" to "thenLaunch timeout: slow"), failureEvents)
    assertEquals(2, failureMessages.size)
  }

  @Test
  fun `a timeout inside the launched step is a launched failure, not a thenLaunch timeout`() = runTest {
    val ownTimeout = workflow<Seed, Unit, Nothing>("own-timeout") { withTimeout(10.milliseconds) { awaitCancellation() } }
    val useCase: UseCase<Seed, Failure<LaunchError>, List<String>> = useCase("Test") {
      startWith { it }
        .thenLaunch(ownTimeout, timeout = 1.seconds)
        .awaitLaunched()
        .result { events.filterIsInstance<LaunchedFailureEvent>().map { it.message } }
    }

    assertEquals(Either.Right(listOf("An exception occurred")), useCase(Seed("x")))
  }

  @Test
  fun `a thenLaunch timeout is recorded as a failed execution`() = runTest {
    val summaries = mutableListOf<UseCaseSummary>()
    val config = useCaseConfig { onSummary { summaries += it } }
    val useCase: UseCase<Seed, Failure<LaunchError>, String> = useCase("Test", config = config) {
      startWith { it }.thenLaunch(sideEffect("slow", delayMs = 200), timeout = 50.milliseconds).result { it.value }
    }

    useCase(Seed("x"))

    val execution = summaries.single().executions.single { it.workflowId == "slow" }
    assertEquals(false, execution.succeeded)
  }

  @Test
  fun `each awaitLaunched only drains work launched before it`() = runTest {
    val useCase: UseCase<Seed, Failure<LaunchError>, List<String>> = useCase("Test") {
      startWith { it }
        .thenLaunch(sideEffect("first"))
        .awaitLaunched()
        .thenLaunch(sideEffect("second"))
        .awaitLaunched()
        .awaitLaunched()
        .result { labels(events) }
    }

    assertEquals(Either.Right(listOf("first", "second")), useCase(Seed("x")))
  }

  @Test
  fun `cancelling the caller cancels launched work instead of reporting a timeout`() = runTest {
    val started = CompletableDeferred<Unit>()
    var cancelled = false
    val hanging = workflow<Seed, Unit, Nothing>("hanging") {
      started.complete(Unit)
      try {
        awaitCancellation()
      } finally {
        cancelled = true
      }
    }
    val useCase: UseCase<Seed, Failure<LaunchError>, String> = useCase("Test") {
      startWith { it }.thenLaunch(hanging).result { it.value }
    }

    val job = launch { useCase(Seed("x")) }
    started.await()
    job.cancel()
    job.join()

    assertTrue(cancelled)
    assertTrue(job.isCancelled)
  }

  @Test
  fun `concurrent invocations keep their launched work separate`() = runTest {
    val useCase: UseCase<Seed, Failure<LaunchError>, List<String>> = useCase("Test") {
      startWith { it }
        .thenLaunch(workflow<Seed, Unit, Nothing>("echo") { seed -> yield(); emit(SideEffect(seed.value)) })
        .result { labels(events) }
    }

    val results = (1..20).map { n -> async { useCase(Seed("s$n")) } }.awaitAll()

    assertEquals((1..20).map { Either.Right(listOf("s$it")) }, results)
  }
}
