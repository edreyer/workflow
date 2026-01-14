package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import kotlin.test.Test
import kotlin.test.assertTrue

class ParallelJoinCompositionTest {

  data class First(val value: String) : WorkflowState
  data class WrongInput(val value: String) : WorkflowState
  data class Second(val value: String) : WorkflowState
  data class Merged(val a: String, val b: String) : WorkflowState

  class FirstWorkflow(override val id: String) : Workflow<First, First>() {
    override suspend fun executeWorkflow(input: First): Either<WorkflowError, WorkflowResult<First>> = either {
      WorkflowResult(input)
    }
  }

  class WrongInputWorkflow(override val id: String) : Workflow<WrongInput, Second>() {
    override suspend fun executeWorkflow(input: WrongInput): Either<WorkflowError, WorkflowResult<Second>> = either {
      WorkflowResult(Second(input.value))
    }
  }

  @Test
  fun `parallelJoin fails with composition error on input mismatch`() {
    @Suppress("UNCHECKED_CAST")
    val wrongWorkflow = WrongInputWorkflow("wrong") as Workflow<First, Second>

    val useCase = useCase<FirstCommand> {
      startWith { cmd -> Either.Right(First(cmd.value)) }
      parallelJoin<First, First, Second, Merged>(
        FirstWorkflow("first"),
        wrongWorkflow
      ) { a, b -> Merged(a.value, b.value) }
    }

    val result = kotlinx.coroutines.runBlocking { useCase.execute(FirstCommand("x")) }

    assertTrue(result is Either.Left)
    val error = result.value
    assertTrue(
      error is WorkflowError.CompositionError || error is WorkflowError.ExecutionContextError,
      "Expected composition-related error"
    )
  }

  data class FirstCommand(val value: String) : UseCaseCommand
}
