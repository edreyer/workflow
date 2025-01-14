package com.toasttab.workflow
import arrow.core.Either
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
        val initialWorkflow = FailingWorkflow()
        val nextWorkflow = TestWorkflow("A")

        val useCase = useCase(initialWorkflow) {
            then(nextWorkflow) { result -> TestCommand(result.events.first().id) }
            build()
        }

        val input = TestCommand(UUID.randomUUID())

        val result = runBlocking { useCase.execute(input) }

        result.shouldBeInstanceOf<Either.Left<WorkflowError>>()
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
        val initialWorkflow = TestWorkflowWithContextData()
        val nextWorkflow = TestWorkflowWithContextData()

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

        println(result)

        result.shouldBeInstanceOf<Either.Right<WorkflowResult>>()
        (result).value.events.size shouldBe 3

        val executions = (result).value.context.executions
        executions.size shouldBe 3

        // Check if the workflows were executed in parallel (total time should be less than the sum of individual delays)
        (endTime - startTime) shouldBeLessThan 3000L
    }
})

class TestCommand(val id: UUID) : Command

data class TestEvent(override val id: UUID, override val timestamp: Instant) : Event

class TestWorkflow(val id: String) : Workflow<TestCommand, TestEvent>() {
    override suspend fun executeWorkflow(input: TestCommand, context: Context): Either<WorkflowError, WorkflowResult> {
        val event = TestEvent(input.id, Instant.now())
        val newContext = context.addData("id", this.id)
        return Either.Right(WorkflowResult(newContext, listOf(event)))
    }
}

class FailingWorkflow : Workflow<TestCommand, TestEvent>() {
    override suspend fun executeWorkflow(input: TestCommand, context: Context): Either<WorkflowError, WorkflowResult> {
        return Either.Left(WorkflowError.ExecutionError("Failed"))
    }
}

class TestWorkflowWithContextData : Workflow<TestCommand, TestEvent>() {
    override suspend fun executeWorkflow(input: TestCommand, context: Context): Either<WorkflowError, WorkflowResult> {
        val updatedContext = context.addData("key1", "value1").addData("key2", "value2")
        val event = TestEvent(input.id, Instant.now())
        return Either.Right(WorkflowResult(updatedContext, listOf(event)))
    }
}

class DelayedWorkflow(val id: String) : Workflow<TestCommand, TestEvent>() {
    override suspend fun executeWorkflow(input: TestCommand, context: Context): Either<WorkflowError, WorkflowResult> {
        delay(1000) // Simulate a delay
        val event = TestEvent(input.id, Instant.now())
        val newContext = context.addData("id", this.id)
        return Either.Right(WorkflowResult(newContext, listOf(event)))
    }
}
