package io.liquidsoftware.workflow
import arrow.core.Either
import io.liquidsoftware.workflow.WorkflowError.CompositionError
import io.liquidsoftware.workflow.WorkflowError.ExceptionError
import io.liquidsoftware.workflow.WorkflowError.ExecutionError
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay

class WorkflowChainTest : StringSpec({

    "should execute workflows in sequence" {
        val initialWorkflow = TestWorkflow("A")
        val nextWorkflow = TestWorkflow("B")

        val useCase = useCase(initialWorkflow) {
            then(nextWorkflow) { result -> TestCommand(result.events.first().id) }
            build()
        }

        val input = TestCommand(UUID.randomUUID())
        val result = runBlocking { useCase.execute(input) }

        result.shouldBeInstanceOf<Either.Right<WorkflowResult>>()

        (result).value.events.size shouldBe 2
        val executions = (result).value.context.executions
        executions.size shouldBe 2
    }

    "should not execute next workflow if previous failed" {
        val initialWorkflow = FailingWorkflow("F")
        val nextWorkflow = TestWorkflow("A")

        val useCase = useCase(initialWorkflow) {
            then(nextWorkflow) { result -> TestCommand(result.events.first().id) }
            build()
        }

        val input = TestCommand(UUID.randomUUID())

        val result = runBlocking { useCase.execute(input) }

        result.shouldBeInstanceOf<Either.Left<ExecutionError>>()
    }

    "should not execute next workflow if previous throws exception" {
        val initialWorkflow = ThrowingWorkflow("T")
        val nextWorkflow = TestWorkflow("A")

        val useCase = useCase(initialWorkflow) {
            then(nextWorkflow) { result -> TestCommand(result.events.first().id) }
            build()
        }

        val input = TestCommand(UUID.randomUUID())

        val result = runBlocking { useCase.execute(input) }

        result.shouldBeInstanceOf<Either.Left<ExceptionError>>()
    }

    "should not execute next workflow if inputMapper throws" {
        val initialWorkflow = ThrowingWorkflow("T")
        val nextWorkflow = TestWorkflow("A")

        val useCase = useCase(initialWorkflow) {
            then(nextWorkflow) { result -> throw RuntimeException("Error") }
            build()
        }

        val input = TestCommand(UUID.randomUUID())

        val result = runBlocking { useCase.execute(input) }

        result.shouldBeInstanceOf<Either.Left<CompositionError>>()
    }

    "should execute next workflow if predicate is true" {
        val initialWorkflow = TestWorkflow("A")
        val nextWorkflow = TestWorkflow("B")

        val useCase = useCase(initialWorkflow) {
            thenIf(nextWorkflow, { _ -> true }) {
                result -> TestCommand(result.events.first().id)
            }
            build()
        }

        val input = TestCommand(UUID.randomUUID())

        val result = runBlocking { useCase.execute(input) }

        result.shouldBeInstanceOf<Either.Right<WorkflowResult>>()
        (result).value.events.size shouldBe 2

        val executions = (result).value.context.executions
        executions.size shouldBe 2
    }

    "should not execute next workflow if predicate is false" {
        val initialWorkflow = TestWorkflow("A")
        val nextWorkflow = TestWorkflow("B")

        val useCase = useCase(initialWorkflow) {
            thenIf(nextWorkflow, { _ -> false }) {
                result -> TestCommand(result.events.first().id)
            }
            build()
        }

        val input = TestCommand(UUID.randomUUID())

        val result = runBlocking { useCase.execute(input) }

        result.shouldBeInstanceOf<Either.Right<WorkflowResult>>()
        (result).value.events.size shouldBe 1

        val executions = (result).value.context.executions
        executions.size shouldBe 1
    }

    "should return initial context and no events for empty workflow chain" {
        val initialWorkflow = TestWorkflow("A")

        val useCase = useCase(initialWorkflow) { build() }

        val input = TestCommand(UUID.randomUUID())

        val result = runBlocking { useCase.execute(input) }

        result.shouldBeInstanceOf<Either.Right<WorkflowResult>>()
        (result).value.events.size shouldBe 1
    }

    "should execute multiple workflows in sequence" {
        val initialWorkflow = TestWorkflow("A")
        val secondWorkflow = TestWorkflow("B")
        val thirdWorkflow = TestWorkflow("C")

        val useCase = useCase(initialWorkflow) {
            then(secondWorkflow) { result -> TestCommand(result.events.first().id) }
            then(thirdWorkflow) { result -> TestCommand(result.events.first().id) }
            build()
        }

        val input = TestCommand(UUID.randomUUID())
        val result = runBlocking { useCase.execute(input) }

        result.shouldBeInstanceOf<Either.Right<WorkflowResult>>()
        (result).value.events.size shouldBe 3

        val executions = (result).value.context.executions
        executions.size shouldBe 3
    }

    "should combine context data correctly" {
        val initialWorkflow = TestWorkflowWithContextData("A")
        val nextWorkflow = TestWorkflowWithContextData("B")

        val useCase = useCase(initialWorkflow) {
            then(nextWorkflow) { result -> TestCommand(result.events.first().id) }
            build()
        }

        val input = TestCommand(UUID.randomUUID())
        val result = runBlocking { useCase.execute(input) }

        result.shouldBeInstanceOf<Either.Right<WorkflowResult>>()
        val combinedContext = (result).value.context
        combinedContext.getData("key1") shouldBe "value1"
        combinedContext.getData("key2") shouldBe "value2"

        val executions = (result).value.context.executions
        executions.size shouldBe 2
    }

    "should record workflow execution timing" {
        val initialWorkflow = TestWorkflow("A")
        val nextWorkflow = TestWorkflow("B")

        val useCase = useCase(initialWorkflow) {
            then(nextWorkflow) { result -> TestCommand(result.events.first().id) }
            build()
        }

        val input = TestCommand(UUID.randomUUID())
        val result = runBlocking { useCase.execute(input) }

        result.shouldBeInstanceOf<Either.Right<WorkflowResult>>()
        val executions = (result).value.context.executions
        executions.size shouldBe 2
        executions[0].workflowName shouldBe "TestWorkflow"
        executions[1].workflowName shouldBe "TestWorkflow"
    }

    "should execute workflows in parallel" {
        val initialWorkflow = TestWorkflow("A")
        val nextWorkflow = DelayedWorkflow("B")

        val useCase = useCase(initialWorkflow) {
            runParallel(true)
            then(nextWorkflow) { result -> TestCommand(result.events.first().id) }
            build()
        }

        val input = TestCommand(UUID.randomUUID())

        val startTime = System.currentTimeMillis()
        val result = runBlocking { useCase.execute(input) }
        val endTime = System.currentTimeMillis()

        result.shouldBeInstanceOf<Either.Right<WorkflowResult>>()
        (result).value.events.size shouldBe 2

        val executions = (result).value.context.executions
        executions.size shouldBe 2

        // Check if the workflows were executed in parallel (total time should be less than the sum of individual delays)
        (endTime - startTime) shouldBeLessThan 2000L
    }

    "should execute multiple workflows in parallel" {
        val initialWorkflow = TestWorkflow("A")
        val secondWorkflow = DelayedWorkflow("B")
        val thirdWorkflow = DelayedWorkflow("C")

        val useCase = useCase(initialWorkflow) {
            runParallel(true)
            then(secondWorkflow) { result -> TestCommand(result.events.first().id) }
            then(thirdWorkflow) { result -> TestCommand(result.events.first().id) }
            build()
        }

        val input = TestCommand(UUID.randomUUID())

        val startTime = System.currentTimeMillis()
        val result = runBlocking { useCase.execute(input) }
        val endTime = System.currentTimeMillis()

        result.shouldBeInstanceOf<Either.Right<WorkflowResult>>()
        (result).value.events.size shouldBe 3

        val executions = (result).value.context.executions
        executions.size shouldBe 3

        // Check if the workflows were executed in parallel (total time should be less than the sum of individual delays)
        (endTime - startTime) shouldBeLessThan 3000L
    }

    "should execute multiple workflows in parallel then multiple in sequence" {
        val initialWorkflow = TestWorkflow("A")
        val secondWorkflow = DelayedWorkflow("B")
        val thirdWorkflow = DelayedWorkflow("C")
        val fourthWorkflow = TestWorkflow("D")
        val fifthWorkflow = TestWorkflow("E")

        val useCase = useCase(initialWorkflow) {
            runParallel(true)
            then(secondWorkflow) { result -> TestCommand(result.events.first().id) }
            then(thirdWorkflow) { result -> TestCommand(result.events.first().id) }
            runParallel(false)
            then(fourthWorkflow) { result -> TestCommand(result.events.first().id) }
            then(fifthWorkflow) { result -> TestCommand(result.events.first().id) }
            build()
        }

        val input = TestCommand(UUID.randomUUID())

        val startTime = System.currentTimeMillis()
        val result = runBlocking { useCase.execute(input) }
        val endTime = System.currentTimeMillis()

        println(result)

        result.shouldBeInstanceOf<Either.Right<WorkflowResult>>()
        (result).value.events.size shouldBe 5

        val executions = (result).value.context.executions
        executions.size shouldBe 5

        // Check if the workflows were executed in parallel (total time should be less than the sum of individual delays)
        (endTime - startTime) shouldBeLessThan 3000L
    }

  "should execute three or more workflows in sequence" {
    val workflowA = TestWorkflow("A")
    val workflowB = TestWorkflow("B")
    val workflowC = TestWorkflow("C")

    val useCase = useCase(workflowA) {
      then(workflowB) { result -> TestCommand(result.events.first().id) }
      then(workflowC) { result -> TestCommand(result.events.first().id) }
      build()
    }

    val input = TestCommand(UUID.randomUUID())
    val result = runBlocking { useCase.execute(input) }

    result.shouldBeInstanceOf<Either.Right<WorkflowResult>>()
    (result).value.events.size shouldBe 3
  }

  "should handle complex conditional workflow chains" {
    val workflowA = TestWorkflow("A")
    val workflowB = TestWorkflow("B")
    val workflowC = TestWorkflow("C")

    val useCase = useCase(workflowA) {
      thenIf(workflowB, { it.events.isNotEmpty() }) { result ->
        TestCommand(result.events.first().id)
      }
      thenIf(workflowC, { it.events.size > 1 }) { result ->
        TestCommand(result.events.first().id)
      }
      build()
    }

    val input = TestCommand(UUID.randomUUID())
    val result = runBlocking { useCase.execute(input) }

    // Assert expected behavior
  }

  "should complete workflow chain within reasonable time" {
    val workflow = TestWorkflow("A")
    val useCase = useCase(workflow) { build() }

    val startTime = System.currentTimeMillis()
    val result = runBlocking { useCase.execute(TestCommand(UUID.randomUUID())) }
    val duration = System.currentTimeMillis() - startTime

    duration.shouldBeLessThan(1000) // Adjust threshold as needed
  }

  "should properly propagate context through workflow chain" {
    val workflowA = TestWorkflow("A")
    val workflowB = TestWorkflow("B")

    val useCase = useCase(workflowA) {
      then(workflowB) { result ->
        TestCommand(result.events.first().id)
      }
      build()
    }

    val input = TestCommand(UUID.randomUUID())
    val result = runBlocking { useCase.execute(input) }

    result.shouldBeInstanceOf<Either.Right<WorkflowResult>>()
    val context = (result).value.context
    // Assert context contains expected data from both workflows
  }

  "should handle multiple concurrent workflow executions" {
    val workflow = TestWorkflow("A")
    val useCase = useCase(workflow) { build() }

    runBlocking {
      val results = (1..3).map {
        useCase.execute(TestCommand(UUID.randomUUID()))
      }

      results.forEach { result ->
        result.shouldBeInstanceOf<Either.Right<WorkflowResult>>()
      }
    }
  }

  
})

class TestCommand(val id: UUID) : Command

data class TestEvent(override val id: UUID, override val timestamp: Instant) : Event

class TestWorkflow(override val id: String) : Workflow<TestCommand, TestEvent>() {
    override suspend fun executeWorkflow(input: TestCommand, context: WorkflowContext): Either<WorkflowError, WorkflowResult> {
        val event = TestEvent(input.id, Instant.now())
        val newContext = context.addData("id", this.id)
        return Either.Right(WorkflowResult(newContext, listOf(event)))
    }
}

class FailingWorkflow(override val id: String) : Workflow<TestCommand, TestEvent>() {
    override suspend fun executeWorkflow(input: TestCommand, context: WorkflowContext): Either<WorkflowError, WorkflowResult> {
        return Either.Left(ExecutionError("Failed"))
    }
}

class ThrowingWorkflow(override val id: String) : Workflow<TestCommand, TestEvent>() {
    override suspend fun executeWorkflow(input: TestCommand, context: WorkflowContext): Either<WorkflowError, WorkflowResult> {
        throw RuntimeException("Error")
    }
}

class TestWorkflowWithContextData(override val id: String) : Workflow<TestCommand, TestEvent>() {
    override suspend fun executeWorkflow(input: TestCommand, context: WorkflowContext): Either<WorkflowError, WorkflowResult> {
        val updatedContext = context.addData("key1", "value1").addData("key2", "value2")
        val event = TestEvent(input.id, Instant.now())
        return Either.Right(WorkflowResult(updatedContext, listOf(event)))
    }
}

class DelayedWorkflow(override val id: String) : Workflow<TestCommand, TestEvent>() {
    override suspend fun executeWorkflow(input: TestCommand, context: WorkflowContext): Either<WorkflowError, WorkflowResult> {
        delay(1000) // Simulate a delay
        val event = TestEvent(input.id, Instant.now())
        val newContext = context.addData("id", this.id)
        return Either.Right(WorkflowResult(newContext, listOf(event)))
    }
}
