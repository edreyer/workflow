package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class FirstMethodTest {

    data class TestCommand(val id: UUID) : UseCaseCommand
    data class TestState(val id: UUID) : WorkflowState
    data class TestEvent(
        override val id: UUID,
        override val timestamp: Instant
    ) : Event

    class TestWorkflow(override val id: String) : Workflow<TestState, TestState>() {
        override suspend fun executeWorkflow(input: TestState): Either<WorkflowError, WorkflowResult<TestState>> = either {
            val event = TestEvent(
                id = UUID.randomUUID(),
                timestamp = Instant.now()
            )
            WorkflowResult(input, listOf(event))
        }
    }

    @Test
    fun `startWith() must be called at least once`() {
        val exception = assertThrows<IllegalStateException> {
            val useCase = useCase<TestCommand> {
                // No startWith() call
            }

            runBlocking { useCase.execute(TestCommand(UUID.randomUUID())) }
        }

        assert(exception.message?.contains("startWith() must be called") == true)
    }

    @Test
    fun `startWith() must be the first method called`() {
        val exception = assertThrows<IllegalStateException> {
            useCase<TestCommand> {
                this.then(TestWorkflow("test"))

                startWith { command -> Either.Right(TestState(command.id)) }
            }
        }

        assert(exception.message?.contains("startWith() must be the first method called") == true)
    }

    @Test
    fun `startWith() cannot be called more than once`() {
        val exception = assertThrows<IllegalStateException> {
            useCase<TestCommand> {
                startWith { command -> Either.Right(TestState(command.id)) }
                startWith { command -> Either.Right(TestState(command.id)) }
            }
        }

        assert(exception.message?.contains("startWith() can only be called once") == true)
    }

    @Test
    fun `valid usage of startWith() method`() {
        val useCase = useCase<TestCommand> {
            startWith { command -> Either.Right(TestState(command.id)) }
            then(TestWorkflow("test"))
            then(TestWorkflow("next"))
        }

        val result = runBlocking { useCase.execute(TestCommand(UUID.randomUUID())) }
        assert(result.isRight())
    }
}
