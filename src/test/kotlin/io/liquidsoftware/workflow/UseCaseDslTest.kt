package io.liquidsoftware.workflow

import arrow.core.Either
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class UseCaseDslTest {

  data class PublicInput(val id: UUID) : UseCaseCommand
  data class PublicOutput(val value: String)
  data class BoundaryError(val message: String)
  data class OtherState(val id: UUID) : WorkflowState

  @Test
  fun `UseCase can be implemented directly`() = runBlocking {
    val useCase: UseCase<Int, WorkflowError, String> =
      UseCase { input, _ -> Either.Right("value-$input") }

    val result = useCase(3)

    assertTrue(result.isRight())
    assertEquals("value-3", (result as Either.Right).value)
  }

  @Test
  fun `UseCase accepts explicit WorkflowContext`() = runBlocking {
    val key = WorkflowContext.CORRELATION_ID
    val useCase: UseCase<String, WorkflowError, String> =
      UseCase { input, context ->
        Either.Right("${input}:${context.get(key) ?: "missing"}")
      }

    val result = useCase("plain", WorkflowContext().addData(key, "ctx-123"))

    assertTrue(result.isRight())
    assertEquals("plain:ctx-123", (result as Either.Right).value)
  }

  @Test
  fun `useCase maps public input directly to initial state and final state to output`() = runBlocking {
    val useCase: UseCase<PublicInput, WorkflowError, PublicOutput> =
      useCase {
        startWith { input: PublicInput -> Either.Right(TestState(input.id)) }
        then(StepWorkflow("A", "step-a"))
        then(StepWorkflow("B", "step-b"))
        result(state<TestState>()) { state -> PublicOutput(state.steps.joinToString(",")) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isRight())
    assertEquals("step-a,step-b", (result as Either.Right).value.value)
  }

  @Test
  fun `useCase maps WorkflowError with errors lambda`() = runBlocking {
    val useCase: UseCase<PublicInput, BoundaryError, PublicOutput> =
      useCase(
        errors = { error ->
          val domainError = error as WorkflowError.DomainError
          BoundaryError("${domainError.code}:${domainError.message}")
        },
      ) {
        startWith { _: PublicInput -> Either.Left(WorkflowError.DomainError("MAPPED", "mapped failure")) }
        result(state<TestState>()) { state -> PublicOutput(state.steps.joinToString(",")) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    assertEquals("MAPPED:mapped failure", (result as Either.Left).value.message)
  }

  @Test
  fun `useCase maps WorkflowError with errors function reference`() = runBlocking {
    val useCase: UseCase<PublicInput, BoundaryError, PublicOutput> =
      useCase(
        errors = ::toBoundaryError,
      ) {
        startWith { _: PublicInput -> Either.Left(WorkflowError.ValidationError("bad input")) }
        result(state<TestState>()) { state -> PublicOutput(state.steps.joinToString(",")) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    assertEquals("bad input", (result as Either.Left).value.message)
  }

  @Test
  fun `failure logging lambda is typed to mapped use case error`() = runBlocking {
    val useCase: UseCase<PublicInput, BoundaryError, PublicOutput> =
      useCase(
        errors = ::toBoundaryError,
      ) {
        startWith { _: PublicInput -> Either.Left(WorkflowError.ValidationError("bad input")) }
        result(state<TestState>()) { state -> PublicOutput(state.steps.joinToString(",")) }
        logging {
          failure { error ->
            val boundaryMessage: String = error.message
            field("messageLength", boundaryMessage.length)
          }
        }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    assertEquals("bad input", (result as Either.Left).value.message)
  }

  @Test
  fun `useCase returns raw WorkflowError when errors mapper is omitted`() = runBlocking {
    val useCase: UseCase<PublicInput, WorkflowError, PublicOutput> =
      useCase {
        startWith { _: PublicInput -> Either.Left(WorkflowError.DomainError("RAW", "raw failure")) }
        result(state<TestState>()) { state -> PublicOutput(state.steps.joinToString(",")) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.DomainError)
    assertEquals("RAW", (error as WorkflowError.DomainError).code)
  }

  @Test
  fun `useCase projects output from the last emitted event of the requested type`() = runBlocking {
    val useCase: UseCase<PublicInput, WorkflowError, PublicOutput> =
      useCase {
        startWith { input: PublicInput -> Either.Right(TestState(input.id)) }
        then(StepWorkflow("A", "step-a"))
        then(StepWorkflow("B", "step-b"))
        result(event<StepEvent>()) { event -> PublicOutput(event.step) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isRight())
    assertEquals("step-b", (result as Either.Right).value.value)
  }

  @Test
  fun `useCase passes explicit WorkflowContext into the workflow chain`() = runBlocking {
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

    val useCase: UseCase<PublicInput, WorkflowError, PublicOutput> =
      useCase {
        startWith { input: PublicInput -> Either.Right(TestState(input.id)) }
        then(ContextEventWorkflow("ctx"))
        result(event<StepEvent>()) { event -> PublicOutput(event.step) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()), WorkflowContext().addData(key, "ctx-999"))

    assertTrue(result.isRight())
    assertEquals("ctx-999", (result as Either.Right).value.value)
  }

  @Test
  fun `useCase maps result mapper exceptions into WorkflowError`() = runBlocking {
    val useCase: UseCase<PublicInput, WorkflowError, PublicOutput> =
      useCase {
        startWith { input: PublicInput -> Either.Right(TestState(input.id)) }
        then(StepWorkflow("A", "step-a"))
        result(state<TestState>()) { _ -> error("bad result mapper") }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.ExceptionError)
    assertEquals("bad result mapper", (error as WorkflowError.ExceptionError).ex.message)
  }

  @Test
  fun `useCase maps custom result projection exceptions into WorkflowError`() = runBlocking {
    val useCase: UseCase<PublicInput, WorkflowError, PublicOutput> =
      useCase {
        startWith { input: PublicInput -> Either.Right(TestState(input.id)) }
        result(
          UseCaseResultProjection { _, _ ->
            error("bad custom projection")
          }
        )
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.ExceptionError)
    assertEquals("bad custom projection", (error as WorkflowError.ExceptionError).ex.message)
  }

  @Test
  fun `useCase maps startWith exceptions into WorkflowError`() = runBlocking {
    val useCase: UseCase<PublicInput, WorkflowError, PublicOutput> =
      useCase {
        startWith<TestState> { _: PublicInput -> error("bad startWith mapper") }
        result(state<TestState>()) { state -> PublicOutput(state.steps.joinToString(",")) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.ExceptionError)
    assertEquals("bad startWith mapper", (error as WorkflowError.ExceptionError).ex.message)
  }

  @Test
  fun `useCase maps startWith exceptions with errors lambda`() = runBlocking {
    val useCase: UseCase<PublicInput, BoundaryError, PublicOutput> =
      useCase(
        errors = { error -> BoundaryError("${error::class.simpleName}:${(error as WorkflowError.ExceptionError).ex.message}") },
      ) {
        startWith<TestState> { _: PublicInput -> error("bad startWith mapper") }
        result(state<TestState>()) { state -> PublicOutput(state.steps.joinToString(",")) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    assertEquals("ExceptionError:bad startWith mapper", (result as Either.Left).value.message)
  }

  @Test
  fun `useCase propagates error mapper exceptions`() {
    val useCase: UseCase<PublicInput, BoundaryError, PublicOutput> =
      useCase(
        errors = { _ -> error("bad error mapper") },
      ) {
        startWith { _: PublicInput -> Either.Left(WorkflowError.DomainError("MAPPED", "mapped failure")) }
        result(state<TestState>()) { state -> PublicOutput(state.steps.joinToString(",")) }
      }

    val exception = assertThrows<IllegalStateException> {
      runBlocking { useCase(PublicInput(UUID.randomUUID())) }
    }

    assertEquals("bad error mapper", exception.message)
  }

  @Test
  fun `useCase requires a result projection`() {
    val exception = assertThrows<IllegalArgumentException> {
      useCase<PublicInput, PublicOutput> {
        startWith { input -> Either.Right(TestState(input.id)) }
      }
    }

    assertEquals("useCase result projection is required", exception.message)
  }

  @Test
  fun `useCase rejects multiple result projections`() {
    val exception = assertThrows<IllegalStateException> {
      useCase<PublicInput, PublicOutput> {
        startWith { input -> Either.Right(TestState(input.id)) }
        result(state<TestState>()) { state -> PublicOutput(state.steps.joinToString(",")) }
        result(state<TestState>()) { state -> PublicOutput(state.steps.joinToString("|")) }
      }
    }

    assertEquals("result() can only be called once", exception.message)
  }

  @Test
  fun `state projection returns CompositionError when final state has wrong type`() = runBlocking {
    val useCase: UseCase<PublicInput, WorkflowError, PublicOutput> =
      useCase {
        startWith { input: PublicInput -> Either.Right(TestState(input.id)) }
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
    val useCase: UseCase<PublicInput, WorkflowError, PublicOutput> =
      useCase {
        startWith { input: PublicInput -> Either.Right(TestState(input.id)) }
        result(event<StepEvent>()) { event -> PublicOutput(event.step) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.CompositionError)
    assertEquals("Missing expected event StepEvent", (error as WorkflowError.CompositionError).ex.message)
  }

  @Test
  fun `useCase maps projection errors with errors mapper`() = runBlocking {
    val useCase: UseCase<PublicInput, BoundaryError, PublicOutput> =
      useCase(
        errors = { error -> BoundaryError(error::class.simpleName ?: "Unknown") },
      ) {
        startWith { input: PublicInput -> Either.Right(TestState(input.id)) }
        result(event<StepEvent>()) { event -> PublicOutput(event.step) }
      }

    val result = useCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    assertEquals("CompositionError", (result as Either.Left).value.message)
  }

  @Test
  fun `useCase supports custom result projection`() = runBlocking {
    val useCase: UseCase<PublicInput, WorkflowError, PublicOutput> =
      useCase {
        startWith { input: PublicInput -> Either.Right(TestState(input.id)) }
        then(StepWorkflow("A", "step-a"))
        then(StepWorkflow("B", "step-b"))
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
