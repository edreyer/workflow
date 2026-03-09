package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UseCaseTelemetryTest {

  data class SeedState(val value: String) : WorkflowState
  data class SeedCommand(val value: String) : UseCaseCommand

  class OkWorkflow(override val id: String) : Workflow<SeedState, SeedState>() {
    override suspend fun executeWorkflow(input: SeedState): Either<WorkflowError, WorkflowResult<SeedState>> = either {
      WorkflowResult(input)
    }
  }

  class FailingWorkflow(override val id: String) : Workflow<SeedState, SeedState>() {
    override suspend fun executeWorkflow(input: SeedState): Either<WorkflowError, WorkflowResult<SeedState>> = either {
      raise(WorkflowError.ExecutionError("fail"))
    }
  }

  class DomainFailingWorkflow(override val id: String) : Workflow<SeedState, SeedState>() {
    override suspend fun executeWorkflow(input: SeedState): Either<WorkflowError, WorkflowResult<SeedState>> = either {
      raise(WorkflowError.DomainError("USER_EXISTS", "User already exists"))
    }
  }

  @Test
  fun `builds summary for success`() {
    val useCase = useCase<SeedCommand> {
      startWith { cmd -> Either.Right(SeedState(cmd.value)) }
      then(OkWorkflow("ok"))
    }

    val result = runBlocking { useCase.execute(SeedCommand("x")) }
    val summary = result.toSummary(
      useCaseName = "Seed",
      executions = result.fold({ emptyList() }, { emptyList() })
    )

    assertTrue(summary.succeeded)
    assertEquals("Seed", summary.useCaseName)
    assertEquals(0, summary.launchedFailures.size)
  }

  @Test
  fun `builds summary for detailed success and derives executions from context`() {
    val useCase = useCase<SeedCommand> {
      startWith { cmd -> Either.Right(SeedState(cmd.value)) }
      then(OkWorkflow("ok"))
    }

    val result = runBlocking { useCase.executeDetailed(SeedCommand("x")) }
    val summary = result.toSummary(useCaseName = "Seed")

    assertTrue(summary.succeeded)
    assertEquals("Seed", summary.useCaseName)
    assertEquals(1, summary.executions.size)
  }

  @Test
  fun `detailed summary should derive correlation id launched failures and duration from context`() {
    val execution = WorkflowExecution(
      workflowName = "workflow",
      workflowId = "wf-1",
      startTime = Instant.parse("2026-03-09T10:00:00Z"),
      endTime = Instant.parse("2026-03-09T10:00:05Z"),
      succeeded = true
    )
    val result = Either.Right(
      UseCaseResult<WorkflowState>(
        state = SeedState("x"),
        events = emptyList(),
        context = WorkflowContext()
          .addData(WorkflowContext.CORRELATION_ID, "corr-1")
          .addData(WorkflowContext.LAUNCHED_FAILURES, listOf("launch-a"))
          .addExecution(execution)
      )
    )

    val summary = result.toSummary(useCaseName = "Seed")

    assertTrue(summary.succeeded)
    assertEquals("corr-1", summary.correlationId)
    assertEquals(listOf("launch-a"), summary.launchedFailures)
    assertEquals(Duration.ofSeconds(5), summary.duration)
    assertEquals(listOf(execution), summary.executions)
  }

  @Test
  fun `builds summary for failure`() {
    val useCase = useCase<SeedCommand> {
      startWith { cmd -> Either.Right(SeedState(cmd.value)) }
      then(FailingWorkflow("fail"))
    }

    val result = runBlocking { useCase.execute(SeedCommand("x")) }
    val summary = result.toSummary(useCaseName = "Seed")

    assertTrue(!summary.succeeded)
    assertEquals("Seed", summary.useCaseName)
    assertEquals("ExecutionError", summary.rootError?.type)
  }

  @Test
  fun `failure summary should derive workflow id and duration from execution context`() {
    val execution = WorkflowExecution(
      workflowName = "workflow",
      workflowId = "wf-2",
      startTime = Instant.parse("2026-03-09T11:00:00Z"),
      endTime = Instant.parse("2026-03-09T11:00:03Z"),
      succeeded = false
    )
    val result: Either<WorkflowError, UseCaseEvents> = Either.Left(
      WorkflowError.ExecutionContextError(
        WorkflowError.ExecutionError("fail"),
        execution
      )
    )
    val summary = result.toSummary(useCaseName = "Seed")

    assertTrue(!summary.succeeded)
    assertEquals("wf-2", summary.rootError?.workflowId)
    assertEquals(Duration.ofSeconds(3), summary.duration)
    assertEquals(listOf(execution), summary.executions)
  }

  @Test
  fun `summary accepts supplied executions and launched failures`() {
    val exec = WorkflowExecution(
      workflowName = "name",
      workflowId = "id",
      startTime = Instant.EPOCH,
      endTime = Instant.EPOCH,
      succeeded = true
    )
    val summary = Either.Right(UseCaseEvents(emptyList())).toSummary(
      useCaseName = "Seed",
      launchedFailures = listOf("lf1"),
      executions = listOf(exec)
    )

    assertTrue(summary.succeeded)
    assertEquals(listOf("lf1"), summary.launchedFailures)
    assertEquals(listOf(exec), summary.executions)
  }

  @Test
  fun `summary should preserve explicit overrides for detailed results`() {
    val contextExec = WorkflowExecution(
      workflowName = "context",
      workflowId = "context-id",
      startTime = Instant.parse("2026-03-09T12:00:00Z"),
      endTime = Instant.parse("2026-03-09T12:00:01Z"),
      succeeded = true
    )
    val overrideExec = WorkflowExecution(
      workflowName = "override",
      workflowId = "override-id",
      startTime = Instant.parse("2026-03-09T12:00:00Z"),
      endTime = Instant.parse("2026-03-09T12:00:02Z"),
      succeeded = true
    )
    val result: Either<WorkflowError, UseCaseResult<WorkflowState>> = Either.Right(
      UseCaseResult<WorkflowState>(
        state = SeedState("x"),
        events = emptyList(),
        context = WorkflowContext(executions = listOf(contextExec))
          .addData(WorkflowContext.CORRELATION_ID, "context-corr")
          .addData(WorkflowContext.LAUNCHED_FAILURES, listOf("context-failure"))
      )
    )

    val summary = result.toSummary(
      useCaseName = "Seed",
      correlationId = "override-corr",
      launchedFailures = listOf("override-failure"),
      executions = listOf(overrideExec),
      duration = Duration.ofSeconds(9)
    )

    assertTrue(summary.succeeded)
    assertEquals("override-corr", summary.correlationId)
    assertEquals(listOf("override-failure"), summary.launchedFailures)
    assertEquals(listOf(overrideExec), summary.executions)
    assertEquals(Duration.ofSeconds(9), summary.duration)
  }

  @Test
  fun `summary surfaces domain error metadata`() {
    val useCase = useCase<SeedCommand> {
      startWith { cmd -> Either.Right(SeedState(cmd.value)) }
      then(DomainFailingWorkflow("domain-fail"))
    }

    val result = runBlocking { useCase.executeDetailed(SeedCommand("x")) }
    val summary = result.toSummary(useCaseName = "Seed")

    assertTrue(!summary.succeeded)
    assertEquals("DomainError", summary.rootError?.type)
    assertTrue(summary.rootError?.message?.contains("USER_EXISTS") == true)
  }

  @Test
  fun `summary should unwrap chain errors to the root cause`() {
    val result: Either<WorkflowError, UseCaseEvents> = Either.Left(
      WorkflowError.ChainError(
        WorkflowError.DomainError("USER_EXISTS", "User already exists")
      )
    )
    val summary = result.toSummary(useCaseName = "Seed")
    val rootError = requireNotNull(summary.rootError)

    assertTrue(!summary.succeeded)
    assertEquals("DomainError", rootError.type)
    assertTrue(rootError.message?.contains("USER_EXISTS") == true)
    assertNull(rootError.workflowId)
  }
}
