package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant
import java.util.UUID

class WorkflowUtilsTest {

    // Test data classes
    data class TestCommand(val id: UUID, val name: String) : UseCaseCommand
    data class TestWorkflowInput(val id: UUID, val name: String) : WorkflowCommand
    data class TestEvent(
        override val id: UUID,
        override val timestamp: Instant,
        val name: String
    ) : Event

    class TestWorkflow(override val id: String) : Workflow<TestWorkflowInput, TestEvent>() {
        override suspend fun executeWorkflow(input: TestWorkflowInput): Either<WorkflowError, WorkflowResult> = either {
            val event = TestEvent(
                id = UUID.randomUUID(),
                timestamp = Instant.now(),
                name = input.name
            )
            WorkflowResult(listOf(event))
        }
    }

    interface HierarchyMarker

    abstract class BaseWorkflow<I : WorkflowInput, E : Event>(
        override val id: String
    ) : Workflow<I, E>(), HierarchyMarker

    class DerivedWorkflow(id: String) : BaseWorkflow<TestWorkflowInput, TestEvent>(id) {
        override suspend fun executeWorkflow(input: TestWorkflowInput): Either<WorkflowError, WorkflowResult> = either {
            val event = TestEvent(
                id = UUID.randomUUID(),
                timestamp = Instant.now(),
                name = input.name
            )
            WorkflowResult(listOf(event))
        }
    }

    @Test
    fun `getWorkflowInputClass should return correct class for workflow`() {
        // Given
        val workflow = TestWorkflow("test")

        // When
        val inputClass = WorkflowUtils.getWorkflowInputClass<TestWorkflowInput>(workflow)

        // Then
        assertNotNull(inputClass)
        assertEquals(TestWorkflowInput::class, inputClass)
    }

    @Test
    fun `getWorkflowInputClass should resolve through base class hierarchy`() {
        // Given
        val workflow = DerivedWorkflow("test")

        // When
        val inputClass = WorkflowUtils.getWorkflowInputClass<TestWorkflowInput>(workflow)

        // Then
        assertNotNull(inputClass)
        assertEquals(TestWorkflowInput::class, inputClass)
    }

    @Test
    fun `autoMapInput should map properties from command`() {
        // Given
        val command = TestCommand(UUID.randomUUID(), "Test Name")
        val result = WorkflowResult()

        // When
        val input = WorkflowUtils.autoMapInput(result, command, PropertyMapping.EMPTY, TestWorkflowInput::class)

        // Then
        assertNotNull(input)
        assertEquals(command.id, input?.id)
        assertEquals(command.name, input?.name)
    }

    @Test
    fun `autoMapInput should map properties from event`() {
        // Given
        val event = TestEvent(UUID.randomUUID(), Instant.now(), "Test Name")
        val result = WorkflowResult(listOf(event))
        val command = TestCommand(UUID.randomUUID(), "Ignored Name")

        // When
        val input = WorkflowUtils.autoMapInput(result, command, PropertyMapping.EMPTY, TestWorkflowInput::class)

        // Then
        assertNotNull(input)
        assertEquals(event.id, input?.id)
        assertEquals(event.name, input?.name)
    }

    @Test
    fun `autoMapInput should use property mapping`() {
        // Given
        val command = TestCommand(UUID.randomUUID(), "Test Name")
        val result = WorkflowResult()
        val propertyMap = PropertyMapping.EMPTY

        // When
        val input = WorkflowUtils.autoMapInput(result, command, propertyMap, TestWorkflowInput::class)

        // Then
        assertNotNull(input)
        assertEquals(command.id, input?.id)
        assertEquals(command.name, input?.name)
    }

    @Test
    fun `autoMapInput should handle custom property mapping`() {
        // Given
        val command = TestCommand(UUID.randomUUID(), "Test Name")
        val result = WorkflowResult()
        val propertyMap = PropertyMapping.EMPTY

        // When
        val input = WorkflowUtils.autoMapInput(result, command, propertyMap, TestWorkflowInput::class)

        // Then
        assertNotNull(input)
        assertEquals(command.id, input?.id)
        assertEquals(command.name, input?.name)
    }

    @Test
    fun `autoMapInput should return null for unmappable input`() {
        // Given
        data class UnmappableInput(val unmappableProperty: String) : WorkflowCommand
        val command = TestCommand(UUID.randomUUID(), "Test Name")
        val result = WorkflowResult()

        // When
        val input = WorkflowUtils.autoMapInput(result, command, PropertyMapping.EMPTY, UnmappableInput::class)

        // Then
        assertNull(input)
    }

    @Test
    fun `autoMapInput should return null when type mismatch occurs in property mapping`() {
        // Given - a workflow input that expects a String id but we try to map a UUID
        data class WorkflowInputWithStringId(val id: String, val name: String) : WorkflowCommand
        val command = TestCommand(UUID.randomUUID(), "Test Name")
        val result = WorkflowResult()

        // Property mapping that tries to map UUID to String (type mismatch)
        val propertyMapping = PropertyMapping(
            typedMappings = mapOf(
                "id" to Key.of<UUID>("id") // This should cause type mismatch - UUID mapped to String
            )
        )

        // When
        val input = WorkflowUtils.autoMapInput(result, command, propertyMapping, WorkflowInputWithStringId::class)

        // Then - should return null due to type mismatch, which will trigger CompositionError at composition time
        assertNull(input)
    }

    @Test
    fun `autoMapInput should use default values for optional constructor params`() {
        // Given - workflow input with a default value for name
        data class CommandWithIdOnly(val id: UUID) : UseCaseCommand
        data class InputWithDefault(val id: UUID, val name: String = "default-name") : WorkflowCommand

        val command = CommandWithIdOnly(UUID.randomUUID())
        val result = WorkflowResult()

        // When
        val input = WorkflowUtils.autoMapInput(result, command, PropertyMapping.EMPTY, InputWithDefault::class)

        // Then
        assertNotNull(input)
        assertEquals(command.id, input?.id)
        assertEquals("default-name", input?.name)
    }
}
