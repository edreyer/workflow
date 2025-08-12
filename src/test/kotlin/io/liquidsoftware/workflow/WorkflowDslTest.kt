package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant
import java.util.UUID

class WorkflowDslTest {

    @Test
    fun `should result in CompositionError when useCase contains field mapping problem`() = runBlocking {
        // Given - Create a workflow that expects a String id but we'll try to map a UUID to it
        data class WorkflowInputWithStringId(val id: String, val name: String) : WorkflowCommand
        data class TestUseCaseCommand(val id: UUID, val name: String) : UseCaseCommand

        data class TestEvent(
            override val id: UUID,
            override val timestamp: Instant,
            val name: String
        ) : Event

        val testWorkflow = object : Workflow<WorkflowInputWithStringId, TestEvent>() {
            override val id = "test-workflow"

            override suspend fun executeWorkflow(input: WorkflowInputWithStringId): Either<WorkflowError, WorkflowResult> = either {
                val event = TestEvent(
                    id = UUID.randomUUID(),
                    timestamp = Instant.now(),
                    name = input.name
                )
                WorkflowResult(listOf(event))
            }
        }

        // When - Create a useCase with type mapping problem (trying to map UUID to String)
        val useCase = useCase<TestUseCaseCommand> {
            first(testWorkflow) {
                "id" from Key.of<UUID>("id")   // This maps UUID to String field - type mismatch!
                "name" from Key.of<String>("name")
            }
        }

        val command = TestUseCaseCommand(UUID.randomUUID(), "Test Name")

        // Then - Execution should result in CompositionError
        val result = useCase.execute(command)

        assertTrue(result.isLeft(), "Expected CompositionError but got successful result")

        when (val error = (result as Either.Left).value) {
            is WorkflowError.CompositionError -> {
                // Verify it's the right type of composition error
                assertTrue(error.message.contains("Cannot auto-map"),
                    "Expected auto-mapping error but got: ${error.message}")
            }
            else -> fail("Expected CompositionError but got: ${error::class.simpleName}")
        }
    }

    @Test
    fun `should result in CompositionError when workflow step contains field mapping problem`() = runBlocking {
        // Given - Create workflows with type mismatch in a workflow chain
        data class FirstWorkflowInput(val id: UUID, val name: String) : WorkflowCommand
        data class SecondWorkflowInput(val id: String, val description: String) : WorkflowCommand  // Expects String id
        data class TestUseCaseCommand(val id: UUID, val name: String) : UseCaseCommand

        data class FirstEvent(
            override val id: UUID,
            override val timestamp: Instant,
            val name: String
        ) : Event

        data class SecondEvent(
            override val id: UUID,
            override val timestamp: Instant,
            val description: String
        ) : Event

        val firstWorkflow = object : Workflow<FirstWorkflowInput, FirstEvent>() {
            override val id = "first-workflow"

            override suspend fun executeWorkflow(input: FirstWorkflowInput): Either<WorkflowError, WorkflowResult> = either {
                val event = FirstEvent(
                    id = input.id,
                    timestamp = Instant.now(),
                    name = input.name
                )
                WorkflowResult(listOf(event))
            }
        }

        val secondWorkflow = object : Workflow<SecondWorkflowInput, SecondEvent>() {
            override val id = "second-workflow"

            override suspend fun executeWorkflow(input: SecondWorkflowInput): Either<WorkflowError, WorkflowResult> = either {
                val event = SecondEvent(
                    id = UUID.randomUUID(),
                    timestamp = Instant.now(),
                    description = input.description
                )
                WorkflowResult(listOf(event))
            }
        }

        // When - Create a useCase with type mapping problem in the second workflow
        // The second workflow expects a String id, but we try to map UUID from the first event
        val useCase = useCase<TestUseCaseCommand> {
            first(firstWorkflow)  // This works fine
            then(secondWorkflow) {
                "id" from Key.of<UUID>("id")           // Type mismatch: SecondWorkflowInput.id is String, but we map UUID
                "description" from Key.of<String>("name")  // This mapping is fine
            }
        }

        val command = TestUseCaseCommand(UUID.randomUUID(), "Test Name")

        // Then - Execution should result in CompositionError
        val result = useCase.execute(command)

        assertTrue(result.isLeft(), "Expected CompositionError but got successful result")

        when (val error = (result as Either.Left).value) {
            is WorkflowError.CompositionError -> {
                // Verify it's the right type of composition error
                assertTrue(error.message.contains("Error mapping input") || error.message.contains("Cannot auto-map"),
                    "Expected mapping error but got: ${error.message}")
            }
            else -> fail("Expected CompositionError but got: ${error::class.simpleName}")
        }
    }
}
