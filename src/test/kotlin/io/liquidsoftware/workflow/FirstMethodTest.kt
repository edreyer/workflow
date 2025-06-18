package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class FirstMethodTest {

    // Simple test workflow and command
    data class TestCommand(val id: UUID) : UseCaseCommand
    data class TestWorkflowInput(val id: UUID) : WorkflowCommand
    data class TestEvent(
        override val id: UUID,
        override val timestamp: Instant
    ) : Event

    class TestWorkflow(override val id: String) : Workflow<TestWorkflowInput, TestEvent>() {
        override suspend fun executeWorkflow(input: TestWorkflowInput): Either<WorkflowError, WorkflowResult> = either {
            val event = TestEvent(
                id = UUID.randomUUID(),
                timestamp = Instant.now()
            )
            WorkflowResult(listOf(event))
        }
    }

    @Test
    fun `first() must be called at least once`() {
        // Should throw an exception when first() is not called
        val exception = assertThrows<IllegalStateException> {
            val useCase = useCase<TestCommand> {
                // No first() call
            }

            // Trigger the build() method which checks if first() was called
            runBlocking { useCase.execute(TestCommand(UUID.randomUUID())) }
        }

        assert(exception.message?.contains("first() method must be called") == true)
    }

    @Test
    fun `first() must be the first method called`() {
        // Should throw an exception when first() is not the first method called
        val exception = assertThrows<IllegalStateException> {
            useCase<TestCommand> {
                this.then(TestWorkflow("test"))

                first(
                    workflow = TestWorkflow("test"),
                    inputMapper = { command -> TestWorkflowInput(command.id) }
                )
            }
        }

        assert(exception.message?.contains("first() method must be the first method called") == true)
    }

    @Test
    fun `first() cannot be called more than once`() {
        // Should throw an exception when first() is called more than once
        val exception = assertThrows<IllegalStateException> {
            useCase<TestCommand> {
                first(
                    workflow = TestWorkflow("test1"),
                    inputMapper = { command -> TestWorkflowInput(command.id) }
                )

                first(
                    workflow = TestWorkflow("test2"),
                    inputMapper = { command -> TestWorkflowInput(command.id) }
                )
            }
        }

        assert(exception.message?.contains("first() method can only be called once") == true)
    }

    @Test
    fun `valid usage of first() method`() {
        // Should not throw an exception when first() is used correctly
        val useCase = useCase<TestCommand> {
            first(
                workflow = TestWorkflow("test"),
                inputMapper = { command -> TestWorkflowInput(command.id) }
            )

          this.then(TestWorkflow("next"))
        }

        // Execute the use case to ensure it works
        val result = runBlocking { useCase.execute(TestCommand(UUID.randomUUID())) }
        assert(result.isRight())
    }
}
