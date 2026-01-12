package io.liquidsoftware.workflow

import arrow.core.Either
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.Instant
import java.util.UUID

class ParallelJoinTest {
  data class JoinInput(val value: String) : WorkflowInput

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

  data class MergedEvent(
    override val id: UUID,
    override val timestamp: Instant,
    val label: String
  ) : Event

  class EmittingWorkflow<E : Event>(
    override val id: String,
    private val events: List<Event>,
    private val delayMs: Long = 0L
  ) : Workflow<JoinInput, E>() {
    override suspend fun executeWorkflow(input: JoinInput): Either<WorkflowError, WorkflowResult> {
      if (delayMs > 0) {
        delay(delayMs)
      }
      return Either.Right(WorkflowResult(events = events))
    }
  }

  class FailingWorkflow(
    override val id: String,
    private val delayMs: Long = 0L
  ) : Workflow<JoinInput, AlphaEvent>() {
    override suspend fun executeWorkflow(input: JoinInput): Either<WorkflowError, WorkflowResult> {
      if (delayMs > 0) {
        delay(delayMs)
      }
      return Either.Left(WorkflowError.ExecutionError("Failure: $id"))
    }
  }

  @Test
  fun `parallelJoin merges events in parameter order and appends merged event`() {
    val alpha = AlphaEvent(UUID.randomUUID(), Instant.EPOCH, "alpha")
    val extra = ExtraEvent(UUID.randomUUID(), Instant.EPOCH, "extra")
    val beta = BetaEvent(UUID.randomUUID(), Instant.EPOCH, "beta")

    val alphaWorkflow = EmittingWorkflow<AlphaEvent>("alpha", listOf(alpha, extra))
    val betaWorkflow = EmittingWorkflow<BetaEvent>("beta", listOf(beta))

    var mergedEvent: MergedEvent? = null
    val join = parallelJoin(alphaWorkflow, betaWorkflow) { a, b ->
      MergedEvent(UUID.randomUUID(), Instant.EPOCH, "${a.label}-${b.label}").also {
        mergedEvent = it
      }
    }

    val result = runBlocking { join.execute(JoinInput("ok")) }

    assertTrue(result is Either.Right)
    val events = result.value.events
    val merged = mergedEvent ?: error("merge was not invoked")
    assertEquals(listOf(alpha, extra, beta, merged), events)
    val executions = result.value.context.executions.map { it.workflowId }
    assertEquals(listOf("alpha", "beta", join.id), executions)
  }

  @Test
  fun `parallelJoin fails when required event is missing`() {
    val alphaWorkflow = EmittingWorkflow<AlphaEvent>("alpha", emptyList())
    val betaEvent = BetaEvent(UUID.randomUUID(), Instant.EPOCH, "beta")
    val betaWorkflow = EmittingWorkflow<BetaEvent>("beta", listOf(betaEvent))

    val join = parallelJoin(alphaWorkflow, betaWorkflow) { _, _ ->
      MergedEvent(UUID.randomUUID(), Instant.EPOCH, "merged")
    }

    val result = runBlocking { join.execute(JoinInput("ok")) }

    assertTrue(result is Either.Left)
    assertTrue(result.value is WorkflowError.ExecutionContextError)
  }

  @Test
  fun `parallelJoin fails when required event appears multiple times`() {
    val alphaOne = AlphaEvent(UUID.randomUUID(), Instant.EPOCH, "alpha-1")
    val alphaTwo = AlphaEvent(UUID.randomUUID(), Instant.EPOCH, "alpha-2")
    val alphaWorkflow = EmittingWorkflow<AlphaEvent>("alpha", listOf(alphaOne, alphaTwo))
    val betaEvent = BetaEvent(UUID.randomUUID(), Instant.EPOCH, "beta")
    val betaWorkflow = EmittingWorkflow<BetaEvent>("beta", listOf(betaEvent))

    val join = parallelJoin(alphaWorkflow, betaWorkflow) { _, _ ->
      MergedEvent(UUID.randomUUID(), Instant.EPOCH, "merged")
    }

    val result = runBlocking { join.execute(JoinInput("ok")) }

    assertTrue(result is Either.Left)
    assertTrue(result.value is WorkflowError.ExecutionContextError)
  }

  @Test
  fun `parallelJoin returns first error by parameter order in WaitAll`() {
    val slowFailure = FailingWorkflow("first", delayMs = 200L)
    val fastFailure = FailingWorkflow("second", delayMs = 0L)

    val join = parallelJoin(slowFailure, fastFailure) { _, _ ->
      MergedEvent(UUID.randomUUID(), Instant.EPOCH, "merged")
    }

    val result = runBlocking { join.execute(JoinInput("ok")) }

    assertTrue(result is Either.Left)
    val error = result.value as WorkflowError.ExecutionContextError
    assertEquals("first", error.execution.workflowId)
    assertTrue(error.error !is WorkflowError.ExecutionContextError)
  }

  @Test
  fun `parallelJoin fail-fast cancels slow branch`() {
    val fastFailure = FailingWorkflow("first", delayMs = 50L)
    val slowWorkflow = EmittingWorkflow<BetaEvent>(
      id = "second",
      events = listOf(BetaEvent(UUID.randomUUID(), Instant.EPOCH, "beta")),
      delayMs = 2000L
    )

    val join = parallelJoin(fastFailure, slowWorkflow, ParallelErrorPolicy.FailFast) { _, _ ->
      MergedEvent(UUID.randomUUID(), Instant.EPOCH, "merged")
    }

    val start = System.currentTimeMillis()
    val result = runBlocking { join.execute(JoinInput("ok")) }
    val elapsed = System.currentTimeMillis() - start

    assertTrue(result is Either.Left)
    assertTrue(elapsed < 1000L)
  }
}
