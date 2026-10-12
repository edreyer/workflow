package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThenLaunchTest {

  data class SeedState(val value: String) : WorkflowState

  data class SideEffectEvent(
    override val id: UUID,
    override val timestamp: Instant,
    val label: String
  ) : Event

  class SideEffectWorkflow(
    override val id: String,
    private val label: String? = null,
    private val delayMs: Long = 0
  ) : Workflow<SeedState, SeedState>() {
    override suspend fun executeWorkflow(input: SeedState): Either<WorkflowError, WorkflowResult<SeedState>> = either {
      if (delayMs > 0) delay(delayMs)
      WorkflowResult(
        state = input,
        events = listOf(SideEffectEvent(UUID.randomUUID(), Instant.EPOCH, label ?: input.value))
      )
    }
  }

  class ContextSideEffect(
    override val id: String,
    private val dataKey: String,
    private val dataValue: String
  ) : Workflow<SeedState, SeedState>() {
    override suspend fun executeWorkflow(input: SeedState): Either<WorkflowError, WorkflowResult<SeedState>> = either {
      WorkflowResult(
        state = input,
        context = WorkflowContext().addData(dataKey, dataValue),
        events = listOf(SideEffectEvent(UUID.randomUUID(), Instant.EPOCH, dataValue))
      )
    }
  }

  class FailingSideEffect(override val id: String) : Workflow<SeedState, SeedState>() {
    override suspend fun executeWorkflow(input: SeedState): Either<WorkflowError, WorkflowResult<SeedState>> = either {
      raise(WorkflowError.ExecutionError("failure: $id"))
    }
  }

  class IdentityWorkflow(override val id: String) : Workflow<SeedState, SeedState>() {
    override suspend fun executeWorkflow(input: SeedState): Either<WorkflowError, WorkflowResult<SeedState>> = either {
      WorkflowResult(input, listOf(SideEffectEvent(UUID.randomUUID(), Instant.EPOCH, "identity")))
    }
  }

  class DeferredSideEffectWorkflow(
    override val id: String,
    private val release: CompletableDeferred<Unit>,
  ) : Workflow<SeedState, SeedState>() {
    override suspend fun executeWorkflow(input: SeedState): Either<WorkflowError, WorkflowResult<SeedState>> = either {
      release.await()
      WorkflowResult(input, listOf(SideEffectEvent(UUID.randomUUID(), Instant.EPOCH, "released")))
    }
  }

  class ReleasingWorkflow(
    override val id: String,
    private val release: CompletableDeferred<Unit>,
    private val onStart: () -> Unit,
  ) : Workflow<SeedState, SeedState>() {
    override suspend fun executeWorkflow(input: SeedState): Either<WorkflowError, WorkflowResult<SeedState>> = either {
      onStart()
      release.complete(Unit)
      WorkflowResult(input, listOf(SideEffectEvent(UUID.randomUUID(), Instant.EPOCH, "releaser")))
    }
  }

  data class SeedCommand(val value: String) : UseCaseCommand

  @Test
  fun `thenLaunch side effects merge when awaited`() {
    val useCase = workflowUseCase<SeedCommand> {
      startWith { command -> Either.Right(SeedState(command.value)) }
      thenLaunch(SideEffectWorkflow("side-1", "first"))
      thenLaunch(SideEffectWorkflow("side-2", "second", delayMs = 10))
      awaitLaunched()
    }

    val result = runBlocking { useCase.execute(SeedCommand("ok")) }

    assertTrue(result is Either.Right)
    val labels = result.value.events.filterIsInstance<SideEffectEvent>().map { it.label }.sorted()
    assertEquals(listOf("first", "second"), labels)
  }

  @Test
  fun `thenLaunch does not block subsequent workflow steps before await`() {
    val releaseLaunchedWorkflow = CompletableDeferred<Unit>()
    var nextStepStarted = false
    val useCase = workflowUseCase<SeedCommand> {
      startWith { command -> Either.Right(SeedState(command.value)) }
      thenLaunch(DeferredSideEffectWorkflow("blocked-side-effect", releaseLaunchedWorkflow))
      then(ReleasingWorkflow("next", releaseLaunchedWorkflow) { nextStepStarted = true })
      awaitLaunched()
    }

    val result = runBlocking {
      withTimeout(1_000L) { useCase.execute(SeedCommand("ok")) }
    }

    assertTrue(result is Either.Right)
    assertTrue(nextStepStarted)
    val labels = result.value.events.filterIsInstance<SideEffectEvent>().map { it.label }.sorted()
    assertEquals(listOf("released", "releaser"), labels)
  }

  @Test
  fun `awaitLaunched records failures without failing the chain`() {
    val useCase = workflowUseCase<SeedCommand> {
      startWith { command -> Either.Right(SeedState(command.value)) }
      thenLaunch(FailingSideEffect("bad"), timeout = Duration.ofMillis(50))
      awaitLaunched()
    }

    val result = runBlocking { useCase.execute(SeedCommand("ok")) }

    assertTrue(result is Either.Right)
    assertTrue(result.value.events.filterIsInstance<SideEffectEvent>().isEmpty())
    val failureEvents = result.value.events.filterIsInstance<LaunchedFailureEvent>()
    assertTrue(failureEvents.any { it.message.contains("bad") })
  }

  @Test
  fun `awaitLaunched merges context and events from launched workflows`() {
    val useCase = workflowUseCase<SeedCommand> {
      startWith { command -> Either.Right(SeedState(command.value)) }
      thenLaunch(ContextSideEffect("c1", "k1", "v1"))
      thenLaunch(ContextSideEffect("c2", "k2", "v2"))
      awaitLaunched()
    }

    val result = runBlocking { useCase.execute(SeedCommand("ok")) }

    assertTrue(result is Either.Right)
    val labels = result.value.events.filterIsInstance<SideEffectEvent>().map { it.label }.sorted()
    assertEquals(listOf("v1", "v2"), labels)
  }

  @Test
  fun `awaitLaunched records failures alongside successes`() {
    val useCase = workflowUseCase<SeedCommand> {
      startWith { command -> Either.Right(SeedState(command.value)) }
      thenLaunch(SideEffectWorkflow("ok", "good"))
      thenLaunch(FailingSideEffect("bad"))
      awaitLaunched()
    }

    val result = runBlocking { useCase.execute(SeedCommand("ok")) }

    assertTrue(result is Either.Right)
    val labels = result.value.events.filterIsInstance<SideEffectEvent>().map { it.label }
    assertTrue(labels.contains("good"))
    val failureEvents = result.value.events.filterIsInstance<LaunchedFailureEvent>()
    assertTrue(failureEvents.any { it.message.contains("bad") })
  }

  @Test
  fun `launched failure events name the workflow that failed`() {
    val useCase = workflowUseCase<SeedCommand> {
      startWith { command -> Either.Right(SeedState(command.value)) }
      thenLaunch(SideEffectWorkflow("ok", "good"))
      thenLaunch(FailingSideEffect("audit"))
      thenLaunch(SideEffectWorkflow("slow", "late", delayMs = 200), timeout = Duration.ofMillis(50))
      awaitLaunched()
    }

    val result = runBlocking { useCase.execute(SeedCommand("ok")) }

    assertTrue(result is Either.Right)
    val failureEvents = result.value.events.filterIsInstance<LaunchedFailureEvent>()
    assertEquals(
      listOf("audit" to "failure: audit", "slow" to "thenLaunch timeout: slow"),
      failureEvents.map { it.workflowId to it.message },
    )
  }

  @Test
  fun `thenLaunch timeout yields failure event`() {
    val useCase = workflowUseCase<SeedCommand> {
      startWith { command -> Either.Right(SeedState(command.value)) }
      thenLaunch(SideEffectWorkflow("slow", "late", delayMs = 200), timeout = Duration.ofMillis(50))
      awaitLaunched()
    }

    val result = runBlocking { useCase.execute(SeedCommand("ok")) }

    assertTrue(result is Either.Right)
    val failureEvents = result.value.events.filterIsInstance<LaunchedFailureEvent>()
    assertTrue(failureEvents.any { it.message.contains("timeout") || it.message.contains("slow") })
  }

  @Test
  fun `multiple launch batches stay isolated`() {
    val useCase = workflowUseCase<SeedCommand> {
      startWith { command -> Either.Right(SeedState(command.value)) }
      thenLaunch(SideEffectWorkflow("first", "a1"))
      awaitLaunched()
      thenLaunch(SideEffectWorkflow("second", "b1"))
      awaitLaunched()
    }

    val result = runBlocking { useCase.execute(SeedCommand("ok")) }

    assertTrue(result is Either.Right)
    val labels = result.value.events.filterIsInstance<SideEffectEvent>().map { it.label }.sorted()
    assertEquals(listOf("a1", "b1"), labels)
  }

  @Test
  fun `double await with no pending is a no-op`() {
    val useCase = workflowUseCase<SeedCommand> {
      startWith { command -> Either.Right(SeedState(command.value)) }
      awaitLaunched()
      awaitLaunched()
    }

    val result = runBlocking { useCase.execute(SeedCommand("ok")) }

    assertTrue(result is Either.Right)
    assertTrue(result.value.events.isEmpty())
  }

  @Test
  fun `awaitLaunched responds to cancellation`() {
    val useCase = workflowUseCase<SeedCommand> {
      startWith { command -> Either.Right(SeedState(command.value)) }
      thenLaunch(SideEffectWorkflow("long", "late", delayMs = 500))
      awaitLaunched()
    }

    val result = runBlocking {
      val attempt = runCatching {
        withTimeout(50L) { useCase.execute(SeedCommand("ok")) }
      }
      attempt.getOrElse {
        Either.Left(WorkflowError.ExecutionError("cancelled"))
      }
    }

    assertTrue(result is Either.Left || result is Either.Right)
  }

  @Test
  fun `awaitLaunched preserves state for subsequent workflow`() {
    val useCase = workflowUseCase<SeedCommand> {
      startWith { command -> Either.Right(SeedState(command.value)) }
      thenLaunch(SideEffectWorkflow("first", "e1"))
      awaitLaunched()
      then(IdentityWorkflow("id"))
    }

    val result = runBlocking { useCase.execute(SeedCommand("ok")) }

    assertTrue(result is Either.Right)
    val labels = result.value.events.filterIsInstance<SideEffectEvent>().map { it.label }
    assertTrue(labels.contains("e1"))
  }

  @Test
  fun `concurrent invocations isolate launched workflows per use case execution`() = runBlocking {
    val useCase = workflowUseCase<SeedCommand> {
      startWith { command -> Either.Right(SeedState(command.value)) }
      thenLaunch(SideEffectWorkflow("side-effect", delayMs = 25))
      awaitLaunched()
    }

    val results = coroutineScope {
      (1..25)
        .map { index ->
          async {
            val value = "command-$index"
            value to useCase.execute(SeedCommand(value))
          }
        }
        .awaitAll()
    }

    results.forEach { (value, result) ->
      assertTrue(result is Either.Right)
      val labels = result.value.events.filterIsInstance<SideEffectEvent>().map { it.label }
      assertEquals(listOf(value), labels)
    }
  }
}
