package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class WorkflowChainTest {

  @Test
  fun `should execute workflows in sequence`() {
    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      then(StepWorkflow("A", "step-a"))
      then(StepWorkflow("B", "step-b"))
    }

    val result = runBlocking { useCase.execute(TestCommand(UUID.randomUUID())) }

    assertTrue(result.isRight())
    val events = (result as Either.Right).value.events.filterIsInstance<StepEvent>()
    assertEquals(listOf("step-a", "step-b"), events.map { it.step })
  }

  @Test
  fun `should return error when workflow fails`() {
    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      then(FailingWorkflow("fail"))
      then(StepWorkflow("B", "step-b"))
    }

    val result = runBlocking { useCase.execute(TestCommand(UUID.randomUUID())) }

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.ExecutionContextError)
  }

  @Test
  fun `should return error when workflow throws`() {
    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      then(ThrowingWorkflow("throw"))
      then(StepWorkflow("B", "step-b"))
    }

    val result = runBlocking { useCase.execute(TestCommand(UUID.randomUUID())) }

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.ExecutionContextError)
  }

  @Test
  fun `should execute next workflow when predicate is true`() {
    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      then(StepWorkflow("A", "step-a"))
      thenIf(StepWorkflow("B", "step-b")) { result ->
        result.state is TestState
      }
    }

    val result = runBlocking { useCase.execute(TestCommand(UUID.randomUUID())) }

    assertTrue(result.isRight())
    val events = (result as Either.Right).value.events.filterIsInstance<StepEvent>()
    assertEquals(listOf("step-a", "step-b"), events.map { it.step })
  }

  @Test
  fun `should skip next workflow when predicate is false`() {
    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      then(StepWorkflow("A", "step-a"))
      thenIf(StepWorkflow("B", "step-b")) { _ -> false }
    }

    val result = runBlocking { useCase.execute(TestCommand(UUID.randomUUID())) }

    assertTrue(result.isRight())
    val events = (result as Either.Right).value.events.filterIsInstance<StepEvent>()
    assertEquals(listOf("step-a"), events.map { it.step })
  }

  @Test
  fun `should execute parallel side effects and preserve event order`() {
    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      then(StepWorkflow("A", "step-a"))
      parallel {
        then(DelayedSideEffectWorkflow("P1", "parallel-1", 200L))
        then(DelayedSideEffectWorkflow("P2", "parallel-2", 200L))
      }
      then(StepWorkflow("B", "step-b"))
    }

    val result = runBlocking { useCase.execute(TestCommand(UUID.randomUUID())) }

    assertTrue(result.isRight())
    val events = (result as Either.Right).value.events.filterIsInstance<StepEvent>()
    assertEquals(listOf("step-a", "parallel-1", "parallel-2", "step-b"), events.map { it.step })
  }

  @Test
  fun `should return error when parallel side effect fails`() {
    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      parallel {
        then(FailingWorkflow("fail-parallel"))
      }
      then(StepWorkflow("B", "step-b"))
    }

    val result = runBlocking { useCase.execute(TestCommand(UUID.randomUUID())) }

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.ExecutionContextError)
  }
}

data class TestCommand(val id: UUID) : UseCaseCommand
data class TestState(val id: UUID, val steps: List<String> = emptyList()) : WorkflowState

data class StepEvent(
  override val id: UUID,
  override val timestamp: Instant,
  val step: String
) : Event

class StepWorkflow(
  override val id: String,
  private val step: String
) : Workflow<TestState, TestState>() {
  override suspend fun executeWorkflow(input: TestState): Either<WorkflowError, WorkflowResult<TestState>> = either {
    val event = StepEvent(UUID.randomUUID(), Instant.now(), step)
    WorkflowResult(input.copy(steps = input.steps + step), listOf(event))
  }
}

class DelayedSideEffectWorkflow(
  override val id: String,
  private val step: String,
  private val delayMs: Long
) : Workflow<TestState, TestState>() {
  override suspend fun executeWorkflow(input: TestState): Either<WorkflowError, WorkflowResult<TestState>> = either {
    delay(delayMs)
    val event = StepEvent(UUID.randomUUID(), Instant.now(), step)
    WorkflowResult(input, listOf(event))
  }
}

class FailingWorkflow(override val id: String) : Workflow<TestState, TestState>() {
  override suspend fun executeWorkflow(input: TestState): Either<WorkflowError, WorkflowResult<TestState>> {
    return Either.Left(WorkflowError.ExecutionError("Failed"))
  }
}

class ThrowingWorkflow(override val id: String) : Workflow<TestState, TestState>() {
  override suspend fun executeWorkflow(input: TestState): Either<WorkflowError, WorkflowResult<TestState>> {
    throw RuntimeException("Error")
  }
}
