package io.liquidsoftware.workflow

import arrow.core.Either
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class UseCaseBuilderSourceCompatibilityTest {

  data class RegisterCommand(val id: UUID) : UseCaseCommand
  data class RegisterState(val id: UUID, val steps: List<String> = emptyList()) : WorkflowState
  data class RegisterOutput(val value: String)
  data class RegisterError(val reason: String)

  class MarkRegisteredWorkflow : Workflow<RegisterState, RegisterState>() {
    override val id: String = "mark-registered"

    override suspend fun executeWorkflow(
      input: RegisterState,
      context: WorkflowContext
    ): Either<WorkflowError, WorkflowResult<RegisterState>> =
      Either.Right(WorkflowResult(input.copy(steps = input.steps + "registered"), context = context))
  }

  @Test
  fun `consumer helper extensions can target raw workflow error use case builders`() = runBlocking {
    val useCase: UseCase<RegisterCommand, WorkflowError, RegisterOutput> =
      useCase(name = "Register") {
        startWithRegisterState()
        then(MarkRegisteredWorkflow())
        outputFromRegisterState()
      }

    val result = useCase(RegisterCommand(UUID.randomUUID()))

    assertTrue(result.isRight())
    assertEquals("registered", (result as Either.Right).value.value)
  }

  @Test
  fun `consumer helper extensions can target mapped error use case builders`() = runBlocking {
    val useCase: UseCase<RegisterCommand, RegisterError, RegisterOutput> =
      useCase(
        name = "Register",
        errors = ::toRegisterError,
      ) {
        startWithRegisterState()
        outputFromRegisterState()
        registerFailureLogging()
      }

    val result = useCase(RegisterCommand(UUID.randomUUID()))

    assertTrue(result.isRight())
    assertEquals("", (result as Either.Right).value.value)
  }
}

private fun <E> UseCaseBuilder<
  UseCaseBuilderSourceCompatibilityTest.RegisterCommand,
  E,
  UseCaseBuilderSourceCompatibilityTest.RegisterOutput,
>.startWithRegisterState() {
  startWith { command -> Either.Right(UseCaseBuilderSourceCompatibilityTest.RegisterState(command.id)) }
}

private fun <E> UseCaseBuilder<
  UseCaseBuilderSourceCompatibilityTest.RegisterCommand,
  E,
  UseCaseBuilderSourceCompatibilityTest.RegisterOutput,
>.outputFromRegisterState() {
  result(state<UseCaseBuilderSourceCompatibilityTest.RegisterState>()) { state ->
    UseCaseBuilderSourceCompatibilityTest.RegisterOutput(state.steps.joinToString(","))
  }
}

private fun UseCaseBuilder<
  UseCaseBuilderSourceCompatibilityTest.RegisterCommand,
  UseCaseBuilderSourceCompatibilityTest.RegisterError,
  UseCaseBuilderSourceCompatibilityTest.RegisterOutput,
>.registerFailureLogging() {
  logging {
    failure { error ->
      field("reason", error.reason)
    }
  }
}

private fun toRegisterError(error: WorkflowError): UseCaseBuilderSourceCompatibilityTest.RegisterError =
  UseCaseBuilderSourceCompatibilityTest.RegisterError(error::class.simpleName ?: "WorkflowError")
