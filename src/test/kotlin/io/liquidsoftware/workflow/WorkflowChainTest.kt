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

  @Test
  fun `executeDetailed should expose final state and context`() {
    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      then(StepWorkflow("A", "step-a"))
      then(StepWorkflow("B", "step-b"))
    }

    val result = runBlocking { useCase.executeDetailed(TestCommand(UUID.randomUUID())) }

    assertTrue(result.isRight())
    val detailed = (result as Either.Right).value
    val finalState = detailed.state as TestState
    assertEquals(listOf("step-a", "step-b"), finalState.steps)
    assertEquals(2, detailed.context.executions.size)
  }

  @Test
  fun `executeForEvent should project the last matching event`() {
    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      then(StepWorkflow("A", "step-a"))
      then(StepWorkflow("B", "step-b"))
    }

    val result = runBlocking { useCase.executeForEvent<StepEvent>(TestCommand(UUID.randomUUID())) }

    assertTrue(result.isRight())
    assertEquals("step-b", (result as Either.Right).value.step)
  }

  @Test
  fun `executeForState should project the final state`() {
    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      then(StepWorkflow("A", "step-a"))
      then(StepWorkflow("B", "step-b"))
    }

    val result = runBlocking { useCase.executeForState<TestState>(TestCommand(UUID.randomUUID())) }

    assertTrue(result.isRight())
    assertEquals(listOf("step-a", "step-b"), (result as Either.Right).value.steps)
  }

  @Test
  fun `executeProjected should allow custom projections`() {
    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      then(StepWorkflow("A", "step-a"))
      then(StepWorkflow("B", "step-b"))
    }

    val result = runBlocking {
      useCase.executeProjected(TestCommand(UUID.randomUUID())) { detailed ->
        Either.Right("${detailed.events.size}:${(detailed.state as TestState).steps.last()}")
      }
    }

    assertTrue(result.isRight())
    assertEquals("2:step-b", (result as Either.Right).value)
  }

  @Test
  fun `executeProjected should propagate projector failures`() {
    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      then(StepWorkflow("A", "step-a"))
    }

    val result = runBlocking {
      useCase.executeProjected(TestCommand(UUID.randomUUID())) {
        Either.Left(WorkflowError.DomainError("PROJECT", "Projection failed"))
      }
    }

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.DomainError)
    assertEquals("PROJECT", (error as WorkflowError.DomainError).code)
  }

  @Test
  fun `executeForEvent should return composition error when event missing`() {
    val useCase = object : UseCase<TestCommand>() {
      override suspend fun execute(command: TestCommand): Either<WorkflowError, UseCaseEvents> =
        Either.Right(UseCaseEvents(emptyList()))
    }

    val result = runBlocking { useCase.executeForEvent<StepEvent>(TestCommand(UUID.randomUUID())) }

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.CompositionError)
  }

  @Test
  fun `executeForState should return composition error when legacy use case has no typed state`() {
    val useCase = object : UseCase<TestCommand>() {
      override suspend fun execute(command: TestCommand): Either<WorkflowError, UseCaseEvents> =
        Either.Right(
          UseCaseEvents(
            listOf(StepEvent(UUID.randomUUID(), Instant.now(), "legacy"))
          )
        )
    }

    val result = runBlocking { useCase.executeForState<TestState>(TestCommand(UUID.randomUUID())) }

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.CompositionError)
  }

  @Test
  fun `legacy direct use case subclasses should still support executeDetailed`() {
    val useCase = object : UseCase<TestCommand>() {
      override suspend fun execute(command: TestCommand): Either<WorkflowError, UseCaseEvents> =
        Either.Right(
          UseCaseEvents(
            listOf(StepEvent(UUID.randomUUID(), Instant.now(), "legacy"))
          )
        )
    }

    val result = runBlocking { useCase.executeDetailed(TestCommand(UUID.randomUUID())) }

    assertTrue(result.isRight())
    val detailed = (result as Either.Right).value
    assertTrue(detailed.state is EventOnlyState)
    assertEquals(1, detailed.events.size)
    assertTrue(detailed.context.executions.isEmpty())
  }

  @Test
  fun `should support context manipulation within workflow chain`() = runBlocking {
    val key = Key.string("testKey")

    class ContextSettingWorkflow(override val id: String) : Workflow<TestState, TestState>() {
      override suspend fun executeWorkflow(input: TestState, context: WorkflowContext): Either<WorkflowError, WorkflowResult<TestState>> {
        val newContext = context.addData(key, "contextValue")
        return Either.Right(WorkflowResult(input, emptyList(), newContext))
      }
    }

    class ContextReadingWorkflow(override val id: String) : Workflow<TestState, TestState>() {
      override suspend fun executeWorkflow(input: TestState, context: WorkflowContext): Either<WorkflowError, WorkflowResult<TestState>> {
        val value = context.get(key)
        assertEquals("contextValue", value)
        return Either.Right(WorkflowResult(input, listOf(StepEvent(input.id, Instant.now(), value ?: "error"))))
      }
    }

    val useCase = useCase<TestCommand> {
      startWith { cmd -> Either.Right(TestState(cmd.id)) }
      then(ContextSettingWorkflow("set"))
      then(ContextReadingWorkflow("read"))
    }

    val result = useCase.executeDetailed(TestCommand(UUID.randomUUID()))
    assertTrue(result.isRight())
    val detailed = (result as Either.Right).value
    assertEquals("contextValue", detailed.getFromEvent(StepEvent::step))
    assertEquals("contextValue", detailed.context.get(key))
    assertEquals(2, detailed.context.executions.size)
  }

  @Test
  fun `should support passing context from use case boundary`() = runBlocking {
    val key = Key.string("boundaryKey")

    class ContextReadingWorkflow(override val id: String) : Workflow<TestState, TestState>() {
      override suspend fun executeWorkflow(input: TestState, context: WorkflowContext): Either<WorkflowError, WorkflowResult<TestState>> {
        val value = context.get(key)
        assertEquals("boundaryValue", value)
        return Either.Right(WorkflowResult(input, listOf(StepEvent(input.id, Instant.now(), value ?: "error"))))
      }
    }

    val useCase = useCase<TestCommand> {
      startWith { cmd -> Either.Right(TestState(cmd.id)) }
      then(ContextReadingWorkflow("read"))
    }

    val initialContext = WorkflowContext().addData(key, "boundaryValue")
    val result = useCase.executeDetailed(TestCommand(UUID.randomUUID()), initialContext)
    assertTrue(result.isRight())
    val detailed = (result as Either.Right).value
    assertEquals("boundaryValue", detailed.context.get(key))
    assertEquals(1, detailed.context.executions.size)
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
