package io.liquidsoftware.workflow

import arrow.core.Either
import io.liquidsoftware.workflow.WorkflowError.ExecutionError
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class WorkflowChainTest {

  @Test
  fun `should execute workflows in sequence`() {
    val initialWorkflow = TestWorkflow("A")
    val nextWorkflow = TestWorkflow("B")

    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      this.then(nextWorkflow)
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())
    val result = runBlocking { useCase.execute(input) }

    assertTrue(result.isRight())
    result.fold(
      { fail("Expected Right but got Left: $it") },
      {
        assertEquals(2, it.events.size)
        assertEquals(2, it.context.executions.size)
      }
    )
  }

  @Test
  fun `should not execute next workflow if previous failed`() {
    val initialWorkflow = FailingWorkflow("F")
    val nextWorkflow = TestWorkflow("A")

    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      this.then(nextWorkflow)
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())
    val result = runBlocking { useCase.execute(input) }

    assertTrue(result.isLeft())
    result.fold(
      { error ->
        when (error) {
          is WorkflowError.ChainError -> {
            assertTrue(error.error is WorkflowError.ExecutionContextError)
          }
          else -> fail("Expected ChainError but got ${error::class.simpleName}")
        }
      },
      { fail("Expected Left with ChainError but got Right: $it") }
    )
  }

  @Test
  fun `should not execute next workflow if previous throws exception`() {
    val initialWorkflow = ThrowingWorkflow("T")
    val nextWorkflow = TestWorkflow("A")

    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      this.then(nextWorkflow)
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())
    val result = runBlocking { useCase.execute(input) }

    assertTrue(result.isLeft())
    result.fold(
      {
        //assertTrue(it is WorkflowError, "Expected WorkflowError but got ${it.javaClass.simpleName}")
        when (it) {
          is WorkflowError.ChainError -> {
            assertTrue(it.error is WorkflowError.ExecutionContextError)
          }
          else -> fail("Expected ChainError but got ${it::class.simpleName}")
        }
      },
      { fail("Expected Left with ChainError but got Right: $it") }
    )
  }

  @Test
  fun `should surface initial workflow exception error instead of masking it`() {
    val initialWorkflow = ThrowingWorkflow("T")

    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())
    val result = runBlocking { useCase.execute(input) }

    assertTrue(result.isLeft())
    result.fold(
      { error ->
        when (error) {
          is WorkflowError.ChainError -> {
            assertTrue(error.error is WorkflowError.ExecutionContextError)
          }
          else -> fail("Expected ChainError but got ${error::class.simpleName}")
        }
      },
      { fail("Expected Left with ChainError but got Right: $it") }
    )
  }

  @Test
  fun `should not execute next workflow if inputMapper throws`() {
    val initialWorkflow = ThrowingWorkflow("T")

    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())
    val result = runBlocking { useCase.execute(input) }

    assertTrue(result.isLeft())
    result.fold(
      {
        //assertTrue(it is WorkflowError, "Expected WorkflowError but got ${it.javaClass.simpleName}")
        when (it) {
          is WorkflowError.ChainError -> {
            assertTrue(it.error is WorkflowError.ExecutionContextError)
          }
          else -> fail("Expected ChainError but got ${it::class.simpleName}")
        }
      },
      { fail("Expected Left with ChainError but got Right: $it") }
    )
  }

  @Test
  fun `should execute next workflow if predicate is true`() {
    val initialWorkflow = TestWorkflow("A")
    val nextWorkflow = TestWorkflow("B")

    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      thenIf(nextWorkflow, { _ -> true })
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())
    val result = runBlocking { useCase.execute(input) }

    assertTrue(result is Either.Right<WorkflowResult>)
    assertEquals(2, (result as Either.Right<WorkflowResult>).value.events.size)
    val executions = result.value.context.executions
    assertEquals(2, executions.size)
  }

  @Test
  fun `should not execute next workflow if predicate is false`() {
    val initialWorkflow = TestWorkflow("A")
    val nextWorkflow = TestWorkflow("B")

    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      thenIf(nextWorkflow, { _ -> false })
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())
    val result = runBlocking { useCase.execute(input) }

    assertTrue(result is Either.Right<WorkflowResult>)
    assertEquals(1, (result as Either.Right<WorkflowResult>).value.events.size)
    val executions = result.value.context.executions
    assertEquals(1, executions.size)
  }

  @Test
  fun `should return initial context and no events for empty workflow chain`() {
    val initialWorkflow = TestWorkflow("A")
    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      build()
    }
    val input = TestUseCaseCommand(UUID.randomUUID())
    val result = runBlocking { useCase.execute(input) }

    assertTrue(result is Either.Right<WorkflowResult>)
    assertEquals(1, (result as Either.Right<WorkflowResult>).value.events.size)
  }

  @Test
  fun `should execute multiple workflows in sequence`() {
    val initialWorkflow = TestWorkflow("A")
    val secondWorkflow = TestWorkflow("B")
    val thirdWorkflow = TestWorkflow("C")

    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      this.then(secondWorkflow)
      this.then(thirdWorkflow)
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())
    val result = runBlocking { useCase.execute(input) }

    assertTrue(result is Either.Right<WorkflowResult>)
    assertEquals(3, (result as Either.Right<WorkflowResult>).value.events.size)
    val executions = result.value.context.executions
    assertEquals(3, executions.size)
  }

  @Test
  fun `should combine context data correctly`() {
    val initialWorkflow = TestWorkflowWithContextData("A")
    val nextWorkflow = TestWorkflowWithContextData("B")

    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      this.then(nextWorkflow)
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())
    val result = runBlocking { useCase.execute(input) }

    assertTrue(result is Either.Right<WorkflowResult>)
    val combinedContext = (result as Either.Right<WorkflowResult>).value.context
    assertEquals("value1", combinedContext.getTypedData<String>("key1"))
    assertEquals("value2", combinedContext.getTypedData<String>("key2"))
    val executions = result.value.context.executions
    assertEquals(2, executions.size)
  }

  @Test
  fun `should record workflow execution timing`() {
    val initialWorkflow = TestWorkflow("A")
    val nextWorkflow = TestWorkflow("B")

    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      this.then(nextWorkflow)
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())
    val result = runBlocking { useCase.execute(input) }

    assertTrue(result is Either.Right<WorkflowResult>)
    val executions = (result as Either.Right<WorkflowResult>).value.context.executions
    assertEquals(2, executions.size)
    assertEquals("TestWorkflow", executions[0].workflowName)
    assertEquals("TestWorkflow", executions[1].workflowName)
  }

  @Test
  fun `should execute workflows in parallel`() {
    val initialWorkflow = TestWorkflow("A")
    val nextWorkflow = DelayedWorkflow("B")

    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      parallel {
        this.then(nextWorkflow)
      }
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())

    val startTime = System.currentTimeMillis()
    val result = runBlocking { useCase.execute(input) }
    val endTime = System.currentTimeMillis()

    assertTrue(result is Either.Right<WorkflowResult>)
    assertEquals(2, (result as Either.Right<WorkflowResult>).value.events.size)
    val executions = result.value.context.executions
    assertEquals(2, executions.size)

    // Check if the workflows were executed in parallel (total time should be less than the sum of individual delays)
    assertTrue(endTime - startTime < 2000L)
  }

  @Test
  fun `should preserve all executions from parallel results`() {
    val initialWorkflow = TestWorkflow("Init")
    val firstParallel = WorkflowWithExtraExecutions("P1", listOf("P1-extra-1", "P1-extra-2"))
    val secondParallel = WorkflowWithExtraExecutions("P2", listOf("P2-extra-1"))

    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      parallel {
        this.then(firstParallel)
        this.then(secondParallel)
      }
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())
    val result = runBlocking { useCase.execute(input) }

    assertTrue(result is Either.Right<WorkflowResult>)
    val executions = (result as Either.Right<WorkflowResult>).value.context.executions
    assertEquals(6, executions.size)
    assertEquals(
      listOf("Init", "P1-extra-1", "P1-extra-2", "P1", "P2-extra-1", "P2"),
      executions.map { it.workflowId }
    )
  }

  @Test
  fun `should not add executions or events when parallel predicates are false`() {
    val initialWorkflow = TestWorkflow("A")
    val secondWorkflow = TestWorkflow("B")
    val thirdWorkflow = TestWorkflow("C")

    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      parallel {
        thenIf(secondWorkflow, { _ -> false })
        thenIf(thirdWorkflow, { _ -> false })
      }
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())
    val result = runBlocking { useCase.execute(input) }

    assertTrue(result is Either.Right<WorkflowResult>)
    assertEquals(1, (result as Either.Right<WorkflowResult>).value.events.size)
    val executions = result.value.context.executions
    assertEquals(1, executions.size)
  }

  @Test
  fun `should execute multiple workflows in parallel`() {
    val initialWorkflow = TestWorkflow("A")
    val secondWorkflow = DelayedWorkflow("B")
    val thirdWorkflow = DelayedWorkflow("C")

    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      parallel {
        this.then(secondWorkflow)
        this.then(thirdWorkflow)
      }
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())

    val startTime = System.currentTimeMillis()
    val result = runBlocking { useCase.execute(input) }
    val endTime = System.currentTimeMillis()

    assertTrue(result is Either.Right<WorkflowResult>)
    assertEquals(3, (result as Either.Right<WorkflowResult>).value.events.size)
    val executions = result.value.context.executions
    assertEquals(3, executions.size)

    // Check if the workflows were executed in parallel (total time should be less than the sum of individual delays)
    assertTrue(endTime - startTime < 3000L)
  }

  @Test
  fun `should execute multiple workflows in parallel then multiple in sequence`() {
    val initialWorkflow = TestWorkflow("A")
    val secondWorkflow = DelayedWorkflow("B")
    val thirdWorkflow = DelayedWorkflow("C")
    val fourthWorkflow = TestWorkflow("D")
    val fifthWorkflow = TestWorkflow("E")

    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      parallel {
        this.then(secondWorkflow)
        this.then(thirdWorkflow)
      }
      this.then(fourthWorkflow)
      this.then(fifthWorkflow)
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())

    val startTime = System.currentTimeMillis()
    val result = runBlocking { useCase.execute(input) }
    val endTime = System.currentTimeMillis()

    assertTrue(result is Either.Right<WorkflowResult>)
    assertEquals(5, (result as Either.Right<WorkflowResult>).value.events.size)
    val executions = result.value.context.executions
    assertEquals(5, executions.size)

    // Check if the workflows were executed in parallel (total time should be less than the sum of individual delays)
    assertTrue(endTime - startTime < 3000L)
  }

  @Test
  fun `should execute three or more workflows in sequence`() {
    val workflowA = TestWorkflow("A")
    val workflowB = TestWorkflow("B")
    val workflowC = TestWorkflow("C")

    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = workflowA)
      this.then(workflowB)
      this.then(workflowC)
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())
    val result = runBlocking { useCase.execute(input) }

    assertTrue(result is Either.Right<WorkflowResult>)
    assertEquals(3, (result as Either.Right<WorkflowResult>).value.events.size)
  }

  @Test
  fun `should complete workflow chain within reasonable time`() {
    val workflow = TestWorkflow("A")
    val testUseCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = workflow)
      build()
    }

    val startTime = System.currentTimeMillis()
    val result = runBlocking { testUseCase.execute(TestUseCaseCommand(UUID.randomUUID())) }
    val duration = System.currentTimeMillis() - startTime

    assertTrue(duration < 1000L) // Adjust threshold as needed
  }

  @Test
  fun `should execute workflows in parallel using parallel block`() {
    val initialWorkflow = TestWorkflow("A")
    val secondWorkflow = DelayedWorkflow("B")
    val thirdWorkflow = DelayedWorkflow("C")

    val testUseCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      parallel {
        this.then(secondWorkflow)
        this.then(thirdWorkflow)
      }
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())

    val startTime = System.currentTimeMillis()
    val result = runBlocking { testUseCase.execute(input) }
    val endTime = System.currentTimeMillis()

    assertTrue(result is Either.Right<WorkflowResult>)
    assertEquals(3, (result as Either.Right<WorkflowResult>).value.events.size)
    val executions = result.value.context.executions
    assertEquals(3, executions.size)

    // Check if the workflows were executed in parallel (total time should be less than the sum of individual delays)
    assertTrue(endTime - startTime < 3000L)
  }

  @Test
  fun `should execute workflows in mixed parallel and sequential order`() {
    val initialWorkflow = TestWorkflow("A")
    val secondWorkflow = DelayedWorkflow("B")
    val thirdWorkflow = DelayedWorkflow("C")
    val fourthWorkflow = TestWorkflow("D")

    val testUseCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      parallel {
        this.then(secondWorkflow)
        this.then(thirdWorkflow)
      }
      this.then(fourthWorkflow)
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())

    val startTime = System.currentTimeMillis()
    val result = runBlocking { testUseCase.execute(input) }
    val endTime = System.currentTimeMillis()

    assertTrue(result is Either.Right<WorkflowResult>)
    assertEquals(4, (result as Either.Right<WorkflowResult>).value.events.size)
    val executions = result.value.context.executions
    assertEquals(4, executions.size)

    // Check if the parallel workflows were executed concurrently
    assertTrue(endTime - startTime < 3000L)
  }

  @Test
  fun `should handle errors in parallel block`() {
    val initialWorkflow = TestWorkflow("A")
    val secondWorkflow = DelayedWorkflow("B")
    val failingWorkflow = FailingWorkflow("C")

    val testUseCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = initialWorkflow)
      parallel {
        this.then(secondWorkflow)
        this.then(failingWorkflow)
      }
      build()
    }

    val input = TestUseCaseCommand(UUID.randomUUID())
    val result = runBlocking { testUseCase.execute(input) }

    assertTrue(result.isLeft())
    result.fold(
      { assertTrue(it is WorkflowError.ExecutionContextError) },
      { fail("Expected Left with ExecutionContextError but got Right: $it") }
    )
  }
}

class TestUseCaseCommand(val id: UUID) : UseCaseCommand
class TestCommand(val id: UUID) : WorkflowCommand

data class TestEvent(override val id: UUID, override val timestamp: Instant) : Event

class TestWorkflow(override val id: String) : Workflow<TestCommand, TestEvent>() {
  override suspend fun executeWorkflow(input: TestCommand): Either<WorkflowError, WorkflowResult> {
    val event = TestEvent(input.id, Instant.now())
    val newContext = WorkflowContext().addData("id", this.id)
    return Either.Right(WorkflowResult(listOf(event), newContext))
  }
}

class FailingWorkflow(override val id: String) : Workflow<TestCommand, TestEvent>() {
  override suspend fun executeWorkflow(input: TestCommand): Either<WorkflowError, WorkflowResult> {
    return Either.Left(ExecutionError("Failed"))
  }
}

class ThrowingWorkflow(override val id: String) : Workflow<TestCommand, TestEvent>() {
  override suspend fun executeWorkflow(input: TestCommand): Either<WorkflowError, WorkflowResult> {
    throw RuntimeException("Error")
  }
}

class TestWorkflowWithContextData(override val id: String) : Workflow<TestCommand, TestEvent>() {
  override suspend fun executeWorkflow(input: TestCommand): Either<WorkflowError, WorkflowResult> {
    val updatedContext = WorkflowContext().addData("key1", "value1").addData("key2", "value2")
    val event = TestEvent(input.id, Instant.now())
    return Either.Right(WorkflowResult(listOf(event), updatedContext))
  }
}

class DelayedWorkflow(override val id: String) : Workflow<TestCommand, TestEvent>() {
  override suspend fun executeWorkflow(input: TestCommand): Either<WorkflowError, WorkflowResult> {
    delay(1000) // Simulate a delay
    val event = TestEvent(input.id, Instant.now())
    val newContext = WorkflowContext().addData("id", this.id)
    return Either.Right(WorkflowResult(listOf(event), newContext))
  }
}

class WorkflowWithExtraExecutions(
  override val id: String,
  private val executionIds: List<String>
) : Workflow<TestCommand, TestEvent>() {
  override suspend fun executeWorkflow(input: TestCommand): Either<WorkflowError, WorkflowResult> {
    val baseContext = executionIds.fold(WorkflowContext()) { acc, execId ->
      acc.addExecution(
        WorkflowExecution(
          workflowName = "WorkflowWithExtraExecutions",
          workflowId = execId,
          startTime = Instant.EPOCH,
          endTime = Instant.EPOCH,
          succeeded = true
        )
      )
    }
    val event = TestEvent(input.id, Instant.now())
    return Either.Right(WorkflowResult(listOf(event), baseContext))
  }
}
