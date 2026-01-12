package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.util.UUID

class WorkflowDslTest {

    @Test
    fun `should return CompositionError when state type does not match workflow input`() = runBlocking {
        data class TestCommand(val id: UUID) : UseCaseCommand
        data class StartState(val id: UUID) : WorkflowState
        data class OtherState(val id: UUID) : WorkflowState

        val workflow = object : Workflow<OtherState, OtherState>() {
            override val id = "mismatch-workflow"

            override suspend fun executeWorkflow(input: OtherState): Either<WorkflowError, WorkflowResult<OtherState>> = either {
                WorkflowResult(input)
            }
        }

        val useCase = useCase<TestCommand> {
            startWith { command -> Either.Right(StartState(command.id)) }
            then(workflow)
        }

        val result = useCase.execute(TestCommand(UUID.randomUUID()))

        assertTrue(result.isLeft(), "Expected CompositionError but got successful result")
        when (val error = (result as Either.Left).value) {
            is WorkflowError.CompositionError -> Unit
            else -> fail("Expected CompositionError but got: ${error::class.simpleName}")
        }
    }
}
