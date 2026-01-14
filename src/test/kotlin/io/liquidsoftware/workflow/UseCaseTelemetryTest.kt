package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
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
}
