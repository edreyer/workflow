package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant
import java.util.UUID

class WorkflowUtilsTest {

    data class TestState(val id: UUID, val name: String) : WorkflowState
    data class TestEvent(
        override val id: UUID,
        override val timestamp: Instant,
        val name: String
    ) : Event

    class TestWorkflow(override val id: String) : Workflow<TestState, TestState>() {
        override suspend fun executeWorkflow(input: TestState): Either<WorkflowError, WorkflowResult<TestState>> = either {
            val event = TestEvent(
                id = UUID.randomUUID(),
                timestamp = Instant.now(),
                name = input.name
            )
            WorkflowResult(input, listOf(event))
        }
    }

    interface HierarchyMarker
    interface ExtraMarker

    abstract class BaseWorkflow<I : WorkflowState>(
        override val id: String
    ) : Workflow<I, I>(), HierarchyMarker

    class DerivedWorkflow(id: String) : BaseWorkflow<TestState>(id) {
        override suspend fun executeWorkflow(input: TestState): Either<WorkflowError, WorkflowResult<TestState>> = either {
            WorkflowResult(input)
        }
    }

    class MultiInterfaceWorkflow(id: String) : BaseWorkflow<TestState>(id), ExtraMarker {
        override suspend fun executeWorkflow(input: TestState): Either<WorkflowError, WorkflowResult<TestState>> = either {
            WorkflowResult(input)
        }
    }

    class NotAWorkflow

    @Test
    fun `getWorkflowInputClass should return correct class for workflow`() {
        // Given
        val workflow = TestWorkflow("test")

        // When
        val inputClass = WorkflowUtils.getWorkflowInputClass<TestState>(workflow)

        // Then
        assertNotNull(inputClass)
        assertEquals(TestState::class, inputClass)
    }

    @Test
    fun `getWorkflowInputClass should resolve through base class hierarchy`() {
        // Given
        val workflow = DerivedWorkflow("test")

        // When
        val inputClass = WorkflowUtils.getWorkflowInputClass<TestState>(workflow)

        // Then
        assertNotNull(inputClass)
        assertEquals(TestState::class, inputClass)
    }

    @Test
    fun `getWorkflowInputClass should resolve when multiple supertypes exist`() {
        // Given
        val workflow = MultiInterfaceWorkflow("test")

        // When
        val inputClass = WorkflowUtils.getWorkflowInputClass<TestState>(workflow)

        // Then
        assertNotNull(inputClass)
        assertEquals(TestState::class, inputClass)
    }

    @Test
    fun `getWorkflowInputClass should return null when class is not a workflow`() {
        val inputClass = WorkflowUtils.getWorkflowInputClass(NotAWorkflow::class)
        assertNull(inputClass)
    }
}
