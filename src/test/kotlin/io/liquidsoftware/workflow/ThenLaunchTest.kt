package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
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
    private val label: String,
    private val delayMs: Long = 0
  ) : Workflow<SeedState, SeedState>() {
    override suspend fun executeWorkflow(input: SeedState): Either<WorkflowError, WorkflowResult<SeedState>> = either {
      if (delayMs > 0) delay(delayMs)
      WorkflowResult(
        state = input,
        events = listOf(SideEffectEvent(UUID.randomUUID(), Instant.EPOCH, label))
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

  data class SeedCommand(val value: String) : UseCaseCommand

  @Test
  fun `thenLaunch side effects merge when awaited`() {
    val useCase = useCase<SeedCommand> {
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
  fun `awaitLaunched records failures without failing the chain`() {
    val useCase = useCase<SeedCommand> {
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
    val useCase = useCase<SeedCommand> {
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
    val useCase = useCase<SeedCommand> {
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
  fun `thenLaunch timeout yields failure event`() {
    val useCase = useCase<SeedCommand> {
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
    val useCase = useCase<SeedCommand> {
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
    val useCase = useCase<SeedCommand> {
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
    val useCase = useCase<SeedCommand> {
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
}
