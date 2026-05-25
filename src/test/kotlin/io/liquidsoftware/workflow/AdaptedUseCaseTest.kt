package io.liquidsoftware.workflow

import arrow.core.Either
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class AdaptedUseCaseTest {

  data class PublicInput(val id: UUID)
  data class PublicOutput(val value: String)
  data class BoundaryError(val message: String)
  data class OtherState(val id: UUID) : WorkflowState

  @Test
  fun `AdaptedUseCase can be implemented directly`() = runBlocking {
    val useCase: AdaptedUseCase<Int, WorkflowError, String> =
      AdaptedUseCase { input, _ -> Either.Right("value-$input") }

    val result = useCase(3)

    assertTrue(result.isRight())
    assertEquals("value-3", (result as Either.Right).value)
  }

  @Test
  fun `AdaptedUseCase accepts explicit WorkflowContext`() = runBlocking {
    val key = WorkflowContext.CORRELATION_ID
    val useCase: AdaptedUseCase<String, WorkflowError, String> =
      AdaptedUseCase { input, context ->
        Either.Right("${input}:${context.get(key) ?: "missing"}")
      }

    val result = useCase("plain", WorkflowContext().addData(key, "ctx-123"))

    assertTrue(result.isRight())
    assertEquals("plain:ctx-123", (result as Either.Right).value)
  }

  @Test
  fun `adaptedUseCase maps public input to command and final state to output`() = runBlocking {
    val useCase: AdaptedUseCase<PublicInput, WorkflowError, PublicOutput> =
      adaptedUseCase {
        input { publicInput: PublicInput -> TestCommand(publicInput.id) }
        workflow {
          startWith { command -> Either.Right(TestState(command.id)) }
          then(StepWorkflow("A", "step-a"))
          then(StepWorkflow("B", "step-b"))
        }
        result(state<TestState>()) { state -> PublicOutput(state.steps.joinToString(",")) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isRight())
    assertEquals("step-a,step-b", (result as Either.Right).value.value)
  }

  @Test
  fun `adaptedUseCase maps WorkflowError with errors lambda`() = runBlocking {
    val useCase: AdaptedUseCase<PublicInput, BoundaryError, PublicOutput> =
      adaptedUseCase(
        errors = { error ->
          val domainError = error as WorkflowError.DomainError
          BoundaryError("${domainError.code}:${domainError.message}")
        },
      ) {
        input { publicInput: PublicInput -> TestCommand(publicInput.id) }
        workflow {
          startWith { _: TestCommand -> Either.Left(WorkflowError.DomainError("MAPPED", "mapped failure")) }
        }
        result(state<TestState>()) { state -> PublicOutput(state.steps.joinToString(",")) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    assertEquals("MAPPED:mapped failure", (result as Either.Left).value.message)
  }

  @Test
  fun `adaptedUseCase maps WorkflowError with errors function reference`() = runBlocking {
    val useCase: AdaptedUseCase<PublicInput, BoundaryError, PublicOutput> =
      adaptedUseCase(
        errors = ::toBoundaryError,
      ) {
        input { publicInput: PublicInput -> TestCommand(publicInput.id) }
        workflow {
          startWith { _: TestCommand -> Either.Left(WorkflowError.ValidationError("bad input")) }
        }
        result(state<TestState>()) { state -> PublicOutput(state.steps.joinToString(",")) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    assertEquals("bad input", (result as Either.Left).value.message)
  }

  @Test
  fun `adaptedUseCase returns raw WorkflowError when errors mapper is omitted`() = runBlocking {
    val useCase: AdaptedUseCase<PublicInput, WorkflowError, PublicOutput> =
      adaptedUseCase {
        input { publicInput: PublicInput -> TestCommand(publicInput.id) }
        workflow {
          startWith { _: TestCommand -> Either.Left(WorkflowError.DomainError("RAW", "raw failure")) }
        }
        result(state<TestState>()) { state -> PublicOutput(state.steps.joinToString(",")) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.DomainError)
    assertEquals("RAW", (error as WorkflowError.DomainError).code)
  }

  @Test
  fun `adaptedUseCase projects output from the last emitted event of the requested type`() = runBlocking {
    val useCase: AdaptedUseCase<PublicInput, WorkflowError, PublicOutput> =
      adaptedUseCase {
        input { publicInput: PublicInput -> TestCommand(publicInput.id) }
        workflow {
          startWith { command -> Either.Right(TestState(command.id)) }
          then(StepWorkflow("A", "step-a"))
          then(StepWorkflow("B", "step-b"))
        }
        result(event<StepEvent>()) { event -> PublicOutput(event.step) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isRight())
    assertEquals("step-b", (result as Either.Right).value.value)
  }

  @Test
  fun `adaptedUseCase passes explicit WorkflowContext into the use case`() = runBlocking {
    val key = WorkflowContext.CORRELATION_ID

    class ContextEventWorkflow(override val id: String) : Workflow<TestState, TestState>() {
      override suspend fun executeWorkflow(
        input: TestState,
        context: WorkflowContext
      ): Either<WorkflowError, WorkflowResult<TestState>> =
        Either.Right(
          WorkflowResult(
            input,
            listOf(StepEvent(UUID.randomUUID(), Instant.now(), context.get(key) ?: "missing")),
            context
          )
        )
    }

    val useCase: AdaptedUseCase<PublicInput, WorkflowError, PublicOutput> =
      adaptedUseCase {
        input { publicInput: PublicInput -> TestCommand(publicInput.id) }
        workflow {
          startWith { command -> Either.Right(TestState(command.id)) }
          then(ContextEventWorkflow("ctx"))
        }
        result(event<StepEvent>()) { event -> PublicOutput(event.step) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()), WorkflowContext().addData(key, "ctx-999"))

    assertTrue(result.isRight())
    assertEquals("ctx-999", (result as Either.Right).value.value)
  }

  @Test
  fun `adaptedUseCase maps input mapper exceptions into WorkflowError`() = runBlocking {
    val useCase: AdaptedUseCase<PublicInput, WorkflowError, PublicOutput> =
      adaptedUseCase {
        input { _: PublicInput -> error("bad input mapper") }
        workflow {
          startWith { command: TestCommand -> Either.Right(TestState(command.id)) }
        }
        result(state<TestState>()) { state -> PublicOutput(state.steps.joinToString(",")) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.ExceptionError)
    assertEquals("bad input mapper", (error as WorkflowError.ExceptionError).ex.message)
  }

  @Test
  fun `adaptedUseCase maps result mapper exceptions into WorkflowError`() = runBlocking {
    val useCase: AdaptedUseCase<PublicInput, WorkflowError, PublicOutput> =
      adaptedUseCase {
        input { publicInput: PublicInput -> TestCommand(publicInput.id) }
        workflow {
          startWith { command -> Either.Right(TestState(command.id)) }
          then(StepWorkflow("A", "step-a"))
        }
        result(state<TestState>()) { _ -> error("bad result mapper") }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.ExceptionError)
    assertEquals("bad result mapper", (error as WorkflowError.ExceptionError).ex.message)
  }

  @Test
  fun `adaptedUseCase propagates error mapper exceptions`() {
    val useCase: AdaptedUseCase<PublicInput, BoundaryError, PublicOutput> =
      adaptedUseCase(
        errors = { _ -> error("bad error mapper") },
      ) {
        input { publicInput: PublicInput -> TestCommand(publicInput.id) }
        workflow {
          startWith { _: TestCommand -> Either.Left(WorkflowError.DomainError("MAPPED", "mapped failure")) }
        }
        result(state<TestState>()) { state -> PublicOutput(state.steps.joinToString(",")) }
      }

    val exception = assertThrows<IllegalStateException> {
      runBlocking { useCase(PublicInput(UUID.randomUUID())) }
    }

    assertEquals("bad error mapper", exception.message)
  }

  @Test
  fun `adaptedUseCase requires an input mapper`() {
    val exception = assertThrows<IllegalArgumentException> {
      adaptedUseCase<PublicInput, TestCommand, PublicOutput> {
        workflow {
          startWith { command -> Either.Right(TestState(command.id)) }
        }
        result(state<TestState>()) { state -> PublicOutput(state.steps.joinToString(",")) }
      }
    }

    assertEquals("adaptedUseCase input mapper is required", exception.message)
  }

  @Test
  fun `adaptedUseCase requires a workflow`() {
    val exception = assertThrows<IllegalArgumentException> {
      adaptedUseCase<PublicInput, TestCommand, PublicOutput> {
        input { publicInput: PublicInput -> TestCommand(publicInput.id) }
        result(state<TestState>()) { state -> PublicOutput(state.steps.joinToString(",")) }
      }
    }

    assertEquals("adaptedUseCase workflow is required", exception.message)
  }

  @Test
  fun `adaptedUseCase requires a result projection`() {
    val exception = assertThrows<IllegalArgumentException> {
      adaptedUseCase<PublicInput, TestCommand, PublicOutput> {
        input { publicInput: PublicInput -> TestCommand(publicInput.id) }
        workflow {
          startWith { command -> Either.Right(TestState(command.id)) }
        }
      }
    }

    assertEquals("adaptedUseCase result projection is required", exception.message)
  }

  @Test
  fun `state projection returns CompositionError when final state has wrong type`() = runBlocking {
    val useCase: AdaptedUseCase<PublicInput, WorkflowError, PublicOutput> =
      adaptedUseCase {
        input { publicInput: PublicInput -> TestCommand(publicInput.id) }
        workflow {
          startWith { command -> Either.Right(TestState(command.id)) }
        }
        result(state<OtherState>()) { state -> PublicOutput(state.id.toString()) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.CompositionError)
    assertEquals("Unexpected final state TestState", (error as WorkflowError.CompositionError).ex.message)
  }

  @Test
  fun `event projection returns CompositionError when matching event is missing`() = runBlocking {
    val useCase: AdaptedUseCase<PublicInput, WorkflowError, PublicOutput> =
      adaptedUseCase {
        input { publicInput: PublicInput -> TestCommand(publicInput.id) }
        workflow {
          startWith { command -> Either.Right(TestState(command.id)) }
        }
        result(event<StepEvent>()) { event -> PublicOutput(event.step) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.CompositionError)
    assertEquals("Missing expected event StepEvent", (error as WorkflowError.CompositionError).ex.message)
  }

  @Test
  fun `adaptedUseCase maps projection errors with errors mapper`() = runBlocking {
    val useCase: AdaptedUseCase<PublicInput, BoundaryError, PublicOutput> =
      adaptedUseCase(
        errors = { error -> BoundaryError(error::class.simpleName ?: "Unknown") },
      ) {
        input { publicInput: PublicInput -> TestCommand(publicInput.id) }
        workflow {
          startWith { command -> Either.Right(TestState(command.id)) }
        }
        result(event<StepEvent>()) { event -> PublicOutput(event.step) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    assertEquals("CompositionError", (result as Either.Left).value.message)
  }

  @Test
  fun `adaptedUseCase supports custom result projection`() = runBlocking {
    val useCase: AdaptedUseCase<PublicInput, WorkflowError, PublicOutput> =
      adaptedUseCase {
        input { publicInput: PublicInput -> TestCommand(publicInput.id) }
        workflow {
          startWith { command -> Either.Right(TestState(command.id)) }
          then(StepWorkflow("A", "step-a"))
          then(StepWorkflow("B", "step-b"))
        }
        result(
          UseCaseResultProjection { result, _ ->
            Either.Right(PublicOutput("events=${result.events.size}"))
          }
        )
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isRight())
    assertEquals("events=2", (result as Either.Right).value.value)
  }

  private fun toBoundaryError(error: WorkflowError): BoundaryError =
    BoundaryError((error as WorkflowError.ValidationError).message)
}
