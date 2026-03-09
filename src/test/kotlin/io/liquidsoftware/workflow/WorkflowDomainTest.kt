package io.liquidsoftware.workflow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class WorkflowDomainTest {
    data class TestState(val name: String) : WorkflowState
    data class TestEvent(override val id: UUID, override val timestamp: Instant) : Event

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
    fun `should add and retrieve data using typed Key`() {
        val stringKey = Key.string("stringKey")
        val intKey = Key.int("intKey")
        val context = WorkflowContext()
            .addData(stringKey, "value1")
            .addData(intKey, 123)

        assertEquals("value1", context.get(stringKey))
        assertEquals(123, context.get(intKey))
        assertNull(context.get(Key.string("non-existent")))
    }

    @Test
    fun `should support standard keys in WorkflowContext`() {
        val context = WorkflowContext()
            .addData(WorkflowContext.CORRELATION_ID, "id-123")
            .addData(WorkflowContext.LAUNCHED_FAILURES, listOf("fail1", "fail2"))

        assertEquals("id-123", context.get(WorkflowContext.CORRELATION_ID))
        assertEquals(listOf("fail1", "fail2"), context.get(WorkflowContext.LAUNCHED_FAILURES))
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
    fun `should combine workflow contexts without duplicating shared execution history`() {
        val prior = WorkflowExecution("workflow1", "id1", Instant.now(), Instant.now(), true)
        val next = WorkflowExecution("workflow2", "id2", Instant.now(), Instant.now(), true)

        val inherited = WorkflowContext(executions = listOf(prior))
        val returned = WorkflowContext(executions = listOf(prior, next))

        val combined = inherited.combine(returned)

        assertEquals(listOf(prior, next), combined.executions)
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

        val result1 = WorkflowResult(TestState("one"), listOf(event1), WorkflowContext().addData("key1", "value1"))
        val result2 = WorkflowResult(TestState("two"), listOf(event2), WorkflowContext().addData("key2", "value2"))

        val combined = result2.mergePrevious(result1)

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
        val result = WorkflowResult(TestState("state"), listOf(event))

        val retrievedId = result.getFromEvent(TestEvent::id)
        assertEquals(eventId, retrievedId)

        val nonExistentProperty = result.getFromEvent(TestEvent::timestamp)
        assertNotNull(nonExistentProperty)
    }

    @Test
    fun `use case result should require expected last event`() {
        val event = TestEvent(UUID.randomUUID(), Instant.now())
        val result = UseCaseResult(
            state = TestState("done"),
            events = listOf(event)
        )

        val extracted = result.requireLastEvent<TestEvent>("TestUseCase")

        assertTrue(extracted.isRight())
        assertEquals(event.id, extracted.fold({ null }, { it.id }))
    }

    @Test
    fun `use case result should return last matching event`() {
        val first = TestEvent(UUID.randomUUID(), Instant.now())
        val second = TestEvent(UUID.randomUUID(), Instant.now())
        val result = UseCaseResult(
            state = TestState("done"),
            events = listOf(first, second)
        )

        val extracted = result.lastEvent<TestEvent>()

        assertEquals(second.id, extracted?.id)
    }

    @Test
    fun `use case result should get property from event`() {
        val eventId = UUID.randomUUID()
        val event = TestEvent(eventId, Instant.now())
        val result = UseCaseResult(
            state = TestState("done"),
            events = listOf(event)
        )

        val extracted = result.getFromEvent(TestEvent::id)

        assertEquals(eventId, extracted)
    }

    @Test
    fun `use case result should return composition error when expected event missing`() {
        val result = UseCaseResult(
            state = TestState("done"),
            events = emptyList()
        )

        val extracted = result.requireLastEvent<TestEvent>("TestUseCase")

        assertTrue(extracted.isLeft())
        val error = extracted.fold({ it }, { null })
        assertTrue(error is WorkflowError.CompositionError)
        assertTrue((error as WorkflowError.CompositionError).message.contains("TestEvent"))
    }

    @Test
    fun `use case result should require expected state`() {
        val result = UseCaseResult(
            state = TestState("done"),
            events = emptyList()
        )

        val extracted = result.requireState<TestState>("TestUseCase")

        assertTrue(extracted.isRight())
        assertEquals("done", extracted.fold({ null }, { it.name }))
    }

    @Test
    fun `use case result should return composition error for unexpected state`() {
        val result = UseCaseResult(
            state = EventOnlyState,
            events = emptyList()
        )

        val extracted = result.requireState<TestState>("TestUseCase")

        assertTrue(extracted.isLeft())
        val error = extracted.fold({ it }, { null })
        assertTrue(error is WorkflowError.CompositionError)
        assertTrue((error as WorkflowError.CompositionError).message.contains("TestState"))
    }

    @Test
    fun `use case result should convert to use case events`() {
        val event = TestEvent(UUID.randomUUID(), Instant.now())
        val result = UseCaseResult(
            state = TestState("done"),
            events = listOf(event)
        )

        val events = result.toUseCaseEvents()

        assertEquals(listOf(event), events.events)
    }

    @Test
    fun `workflow result should convert to use case result`() {
        val event = TestEvent(UUID.randomUUID(), Instant.now())
        val context = WorkflowContext().addData("key", "value")
        val result = WorkflowResult(
            state = TestState("done"),
            events = listOf(event),
            context = context
        )

        val converted = result.toUseCaseResult()

        assertEquals("done", converted.state.name)
        assertEquals(listOf(event), converted.events)
        assertEquals("value", converted.context.getTypedData<String>("key"))
    }

    @Test
    fun `base event should supply defaults`() {
        val event = object : BaseEvent() {}

        assertNotNull(event.id)
        assertNotNull(event.timestamp)
    }
}
