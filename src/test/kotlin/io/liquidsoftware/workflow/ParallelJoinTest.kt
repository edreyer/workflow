package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParallelJoinTest {
  data class JoinState(val value: String) : WorkflowState
  data class AlphaState(val value: String, val alpha: String) : WorkflowState
  data class BetaState(val value: String, val beta: String) : WorkflowState
  data class MergedState(val value: String, val alpha: String, val beta: String) : WorkflowState

  data class AlphaEvent(
    override val id: UUID,
    override val timestamp: Instant,
    val label: String
  ) : Event

  data class BetaEvent(
    override val id: UUID,
    override val timestamp: Instant,
    val label: String
  ) : Event

  data class ExtraEvent(
    override val id: UUID,
    override val timestamp: Instant,
    val label: String
  ) : Event

  class ConsumingWorkflow(
    override val id: String,
    private val eventLabel: String
  ) : Workflow<MergedState, JoinState>() {
    override suspend fun executeWorkflow(input: MergedState): Either<WorkflowError, WorkflowResult<JoinState>> = either {
      WorkflowResult(
        JoinState("${input.alpha}-${input.beta}"),
        listOf(ExtraEvent(UUID.randomUUID(), Instant.EPOCH, eventLabel))
      )
    }
  }

  data class JoinCommand(val value: String) : UseCaseCommand

  class EmittingWorkflow<S : WorkflowState>(
    override val id: String,
    private val delayMs: Long = 0L,
    private val events: List<Event> = emptyList(),
    private val stateFactory: (JoinState) -> S
  ) : Workflow<JoinState, S>() {
    override suspend fun executeWorkflow(input: JoinState): Either<WorkflowError, WorkflowResult<S>> {
      if (delayMs > 0) {
        delay(delayMs)
      }
      return Either.Right(WorkflowResult(stateFactory(input), events))
    }
  }

  class FailingWorkflow<S : WorkflowState>(
    override val id: String,
    private val delayMs: Long = 0L
  ) : Workflow<JoinState, S>() {
    override suspend fun executeWorkflow(input: JoinState): Either<WorkflowError, WorkflowResult<S>> {
      if (delayMs > 0) {
        delay(delayMs)
      }
      return Either.Left(WorkflowError.ExecutionError("Failure: $id"))
    }
  }

  @Test
  fun `parallelJoin merges states and events in parameter order`() {
    val alpha = AlphaEvent(UUID.randomUUID(), Instant.EPOCH, "alpha")
    val extra = ExtraEvent(UUID.randomUUID(), Instant.EPOCH, "extra")
    val beta = BetaEvent(UUID.randomUUID(), Instant.EPOCH, "beta")

    val alphaWorkflow = EmittingWorkflow(
      id = "alpha",
      events = listOf(alpha, extra)
    ) { input -> AlphaState(input.value, "alpha") }
    val betaWorkflow = EmittingWorkflow(
      id = "beta",
      events = listOf(beta)
    ) { input -> BetaState(input.value, "beta") }

    val join = parallelJoin(alphaWorkflow, betaWorkflow) { a, b ->
      MergedState(a.value, a.alpha, b.beta)
    }

    val result = runBlocking { join.execute(JoinState("ok")) }

    assertTrue(result is Either.Right)
    val right = result.value
    assertEquals(MergedState("ok", "alpha", "beta"), right.state)
    assertEquals(listOf(alpha, extra, beta), right.events)
    val executions = right.context.executions.map { it.workflowId }
    assertEquals(listOf("alpha", "beta", join.id), executions)
  }

  @Test
  fun `parallelJoin returns first error by parameter order in WaitAll`() {
    val slowFailure = FailingWorkflow<AlphaState>("first", delayMs = 200L)
    val fastFailure = FailingWorkflow<BetaState>("second", delayMs = 0L)

    val join = parallelJoin(slowFailure, fastFailure) { _, _ ->
      MergedState("unused", "unused", "unused")
    }

    val result = runBlocking { join.execute(JoinState("ok")) }

    assertTrue(result is Either.Left)
    val error = result.value as WorkflowError.ExecutionContextError
    assertEquals("first", error.execution.workflowId)
    assertTrue(error.error !is WorkflowError.ExecutionContextError)
  }

  @Test
  fun `parallelJoin fail-fast cancels slow branch`() {
    val fastFailure = FailingWorkflow<AlphaState>("first", delayMs = 50L)
    val slowWorkflow = EmittingWorkflow(
      id = "second",
      delayMs = 2000L,
      events = listOf(BetaEvent(UUID.randomUUID(), Instant.EPOCH, "beta"))
    ) { input -> BetaState(input.value, "beta") }

    val join = parallelJoin(fastFailure, slowWorkflow, ParallelErrorPolicy.FailFast) { _, _ ->
      MergedState("unused", "unused", "unused")
    }

    val start = System.currentTimeMillis()
    val result = runBlocking { join.execute(JoinState("ok")) }
    val elapsed = System.currentTimeMillis() - start

    assertTrue(result is Either.Left)
    assertTrue(elapsed < 1000L)
  }

  @Test
  fun `parallelJoin works inside useCase with inferred input type`() {
    val alphaEvent = AlphaEvent(UUID.randomUUID(), Instant.EPOCH, "alpha")
    val betaEvent = BetaEvent(UUID.randomUUID(), Instant.EPOCH, "beta")

    val joinUseCase = useCase<JoinCommand> {
      startWith { command -> Either.Right(JoinState(command.value)) }
      parallelJoin(
        EmittingWorkflow(
          id = "alpha",
          events = listOf(alphaEvent)
        ) { input -> AlphaState(input.value, "alpha") },
        EmittingWorkflow(
          id = "beta",
          events = listOf(betaEvent)
        ) { input -> BetaState(input.value, "beta") }
      ) { a, b ->
        MergedState(a.value, a.alpha, b.beta)
      }
    }

    val result = runBlocking { joinUseCase.execute(JoinCommand("ok")) }

    assertTrue(result is Either.Right)
    assertEquals(listOf(alphaEvent, betaEvent), result.value.events)
  }

  @Test
  fun `top-level parallelJoin feeds subsequent workflow`() {
    val alphaEvent = AlphaEvent(UUID.randomUUID(), Instant.EPOCH, "alpha")
    val betaEvent = BetaEvent(UUID.randomUUID(), Instant.EPOCH, "beta")

    val useCase = useCase<JoinCommand> {
      startWith { command -> Either.Right(JoinState(command.value)) }
      parallelJoin(
        EmittingWorkflow(
          id = "alpha",
          events = listOf(alphaEvent)
        ) { input -> AlphaState(input.value, "alpha") },
        EmittingWorkflow(
          id = "beta",
          events = listOf(betaEvent)
        ) { input -> BetaState(input.value, "beta") }
      ) { a, b ->
        MergedState(a.value, a.alpha, b.beta)
      }
      then(ConsumingWorkflow("consume", "joined"))
    }

    val result = runBlocking { useCase.execute(JoinCommand("ok")) }

    assertTrue(result is Either.Right)
    val events = result.value.events
    assertEquals(3, events.size)
    assertTrue(events.containsAll(listOf(alphaEvent, betaEvent)))
    assertTrue(events.any { it is ExtraEvent && it.label == "joined" })
  }
}
