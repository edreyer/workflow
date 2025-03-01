package io.liquidsoftware.workflow

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class WorkflowDomainTest {

    @Test
    fun `should add and retrieve data from WorkflowContext`() {
        val context = WorkflowContext()
            .addData("stringKey", "stringValue")
            .addData("intKey", 42)
            .addData("boolKey", true)

        assertEquals("stringValue", context.getTypedData<String>("stringKey"))
        assertEquals(42, context.getTypedData<Int>("intKey"))
        assertEquals(true, context.getTypedData<Boolean>("boolKey"))
        assertNull(context.getTypedData<String>("nonExistentKey"))
    }

    @Test
    fun `should combine two WorkflowContexts`() {
        val context1 = WorkflowContext()
            .addData("key1", "value1")
            .addExecution(WorkflowExecution("workflow1", "id1", Instant.now(), Instant.now(), true))

        val context2 = WorkflowContext()
            .addData("key2", "value2")
            .addExecution(WorkflowExecution("workflow2", "id2", Instant.now(), Instant.now(), true))

        val combined = context1.combine(context2)

        assertEquals("value1", combined.getTypedData<String>("key1"))
        assertEquals("value2", combined.getTypedData<String>("key2"))
        assertEquals(2, combined.executions.size)
        assertEquals("workflow1", combined.executions[0].workflowName)
        assertEquals("workflow2", combined.executions[1].workflowName)
    }

    @Test
    fun `should add execution to WorkflowContext`() {
        val context = WorkflowContext()
        val execution = WorkflowExecution("testWorkflow", "id1", Instant.now(), Instant.now(), true)

        val updatedContext = context.addExecution(execution)

        assertEquals(1, updatedContext.executions.size)
        assertEquals("testWorkflow", updatedContext.executions[0].workflowName)
    }

    @Test
    fun `should combine WorkflowResults`() {
        val event1 = TestEvent(UUID.randomUUID(), Instant.now())
        val event2 = TestEvent(UUID.randomUUID(), Instant.now())

        val result1 = WorkflowResult(listOf(event1), WorkflowContext().addData("key1", "value1"))
        val result2 = WorkflowResult(listOf(event2), WorkflowContext().addData("key2", "value2"))

        val combined = result1.combine(result2)

        assertEquals(2, combined.events.size)
        assertEquals(event1.id, combined.events[0].id)
        assertEquals(event2.id, combined.events[1].id)
        assertEquals("value1", combined.context.getTypedData<String>("key1"))
        assertEquals("value2", combined.context.getTypedData<String>("key2"))
    }

    @Test
    fun `should get property from event using getFromEvent`() {
        val eventId = UUID.randomUUID()
        val event = TestEvent(eventId, Instant.now())
        val result = WorkflowResult(listOf(event))

        val retrievedId = result.getFromEvent(TestEvent::id)
        assertEquals(eventId, retrievedId)

        val nonExistentProperty = result.getFromEvent(TestEvent::timestamp)
        assertNotNull(nonExistentProperty)
    }
}

