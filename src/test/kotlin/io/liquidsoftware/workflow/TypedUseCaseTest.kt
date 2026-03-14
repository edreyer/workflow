package io.liquidsoftware.workflow

import arrow.core.Either
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class TypedUseCaseTest {

  data class PublicInput(val id: UUID)
  data class PublicOutput(val value: String)
  data class BoundaryError(val message: String)

  @Test
  fun `TypedUseCase can be implemented as a SAM`() = runBlocking {
    val typedUseCase: TypedUseCase<Int, WorkflowError, String> = TypedUseCase { input ->
      Either.Right("value-$input")
    }

    val result = typedUseCase(3)

    assertTrue(result.isRight())
    assertEquals("value-3", (result as Either.Right).value)
  }

  @Test
  fun `ContextualTypedUseCase defaults no-context invocation to empty WorkflowContext`() = runBlocking {
    val key = WorkflowContext.CORRELATION_ID
    val contextualUseCase: ContextualTypedUseCase<String, WorkflowError, String> =
      ContextualTypedUseCase { input, context ->
        Either.Right("${input}:${context.get(key) ?: "missing"}")
      }

    val result = contextualUseCase("plain")

    assertTrue(result.isRight())
    assertEquals("plain:missing", (result as Either.Right).value)
  }

  @Test
  fun `ContextualTypedUseCase accepts explicit WorkflowContext`() = runBlocking {
    val key = WorkflowContext.CORRELATION_ID
    val contextualUseCase: ContextualTypedUseCase<String, WorkflowError, String> =
      ContextualTypedUseCase { input, context ->
        Either.Right("${input}:${context.get(key) ?: "missing"}")
      }

    val result = contextualUseCase("plain", WorkflowContext().addData(key, "ctx-123"))

    assertTrue(result.isRight())
    assertEquals("plain:ctx-123", (result as Either.Right).value)
  }

  @Test
  fun `toStateUseCase maps input and output correctly`() = runBlocking {
    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      then(StepWorkflow("A", "step-a"))
      then(StepWorkflow("B", "step-b"))
    }

    val typedUseCase =
      useCase.toStateUseCase<PublicInput, TestCommand, TestState, PublicOutput>(
        inputMapper = { input -> TestCommand(input.id) },
        outputMapper = { state -> PublicOutput(state.steps.joinToString(",")) },
      )

    val result = typedUseCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isRight())
    assertEquals("step-a,step-b", (result as Either.Right).value.value)
  }

  @Test
  fun `toStateUseCase returns raw WorkflowError when no error mapper is supplied`() = runBlocking {
    val useCase = object : UseCase<TestCommand>() {
      override suspend fun execute(command: TestCommand): Either<WorkflowError, UseCaseEvents> =
        Either.Left(WorkflowError.DomainError("RAW", "raw failure"))
    }

    val typedUseCase =
      useCase.toStateUseCase<PublicInput, TestCommand, TestState, PublicOutput>(
        inputMapper = { input -> TestCommand(input.id) },
        outputMapper = { state -> PublicOutput(state.steps.joinToString(",")) },
      )

    val result = typedUseCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.DomainError)
    assertEquals("RAW", (error as WorkflowError.DomainError).code)
  }

  @Test
  fun `toStateUseCase maps WorkflowError when error mapper is supplied`() = runBlocking {
    val useCase = object : UseCase<TestCommand>() {
      override suspend fun execute(command: TestCommand): Either<WorkflowError, UseCaseEvents> =
        Either.Left(WorkflowError.DomainError("MAPPED", "mapped failure"))
    }

    val typedUseCase =
      useCase.toStateUseCase<PublicInput, TestCommand, TestState, BoundaryError, PublicOutput>(
        inputMapper = { input -> TestCommand(input.id) },
        errorMapper = { error ->
          val domainError = error as WorkflowError.DomainError
          BoundaryError("${domainError.code}:${domainError.message}")
        },
        outputMapper = { state -> PublicOutput(state.steps.joinToString(",")) },
      )

    val result = typedUseCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    assertEquals("MAPPED:mapped failure", (result as Either.Left).value.message)
  }

  @Test
  fun `toStateUseCase propagates error mapper exceptions`() {
    val useCase = object : UseCase<TestCommand>() {
      override suspend fun execute(command: TestCommand): Either<WorkflowError, UseCaseEvents> =
        Either.Left(WorkflowError.DomainError("MAPPED", "mapped failure"))
    }

    val typedUseCase =
      useCase.toStateUseCase<PublicInput, TestCommand, TestState, BoundaryError, PublicOutput>(
        inputMapper = { input -> TestCommand(input.id) },
        errorMapper = { _ -> error("bad error mapper") },
        outputMapper = { state -> PublicOutput(state.steps.joinToString(",")) },
      )

    val exception = assertThrows<IllegalStateException> {
      runBlocking { typedUseCase(PublicInput(UUID.randomUUID())) }
    }

    assertEquals("bad error mapper", exception.message)
  }

  @Test
  fun `toStateUseCase preserves executeForState projection semantics`() = runBlocking {
    val useCase = object : UseCase<TestCommand>() {
      override suspend fun execute(command: TestCommand): Either<WorkflowError, UseCaseEvents> =
        Either.Right(UseCaseEvents(emptyList()))
    }

    val typedUseCase =
      useCase.toStateUseCase<PublicInput, TestCommand, TestState, PublicOutput>(
        inputMapper = { input -> TestCommand(input.id) },
        outputMapper = { state -> PublicOutput(state.steps.joinToString(",")) },
      )

    val result = typedUseCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.CompositionError)
  }

  @Test
  fun `toStateUseCase supports explicit WorkflowContext`() = runBlocking {
    val key = WorkflowContext.CORRELATION_ID
    class ContextStateWorkflow(override val id: String) : Workflow<TestState, TestState>() {
      override suspend fun executeWorkflow(
        input: TestState,
        context: WorkflowContext
      ): Either<WorkflowError, WorkflowResult<TestState>> =
        Either.Right(
          WorkflowResult(
            input.copy(steps = input.steps + (context.get(key) ?: "missing")),
            context = context
          )
        )
    }

    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      then(ContextStateWorkflow("ctx"))
      then(StepWorkflow("A", "step-a"))
    }

    val typedUseCase =
      useCase.toStateUseCase<PublicInput, TestCommand, TestState, PublicOutput>(
        inputMapper = { input -> TestCommand(input.id) },
        outputMapper = { state -> PublicOutput(state.steps.joinToString(",")) },
      )

    val result = typedUseCase(PublicInput(UUID.randomUUID()), WorkflowContext().addData(key, "ctx-123"))

    assertTrue(result.isRight())
    assertEquals("ctx-123,step-a", (result as Either.Right).value.value)
  }

  @Test
  fun `toStateUseCase can be instantiated and invoked from plain Kotlin without Spring`() = runBlocking {
    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      then(StepWorkflow("A", "step-a"))
    }

    val typedUseCase: TypedUseCase<PublicInput, WorkflowError, PublicOutput> =
      useCase.toStateUseCase<PublicInput, TestCommand, TestState, PublicOutput>(
        inputMapper = { input -> TestCommand(input.id) },
        outputMapper = { state -> PublicOutput(state.steps.single()) },
      )

    val result = typedUseCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isRight())
    assertEquals("step-a", (result as Either.Right).value.value)
  }

  @Test
  fun `toStateUseCase maps input mapper exceptions into WorkflowError`() = runBlocking {
    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
    }

    val typedUseCase =
      useCase.toStateUseCase<PublicInput, TestCommand, TestState, PublicOutput>(
        inputMapper = { _ -> error("bad mapper") },
        outputMapper = { state -> PublicOutput(state.steps.joinToString(",")) },
      )

    val result = typedUseCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.ExceptionError)
    assertEquals("bad mapper", (error as WorkflowError.ExceptionError).ex.message)
  }

  @Test
  fun `toStateUseCase maps output mapper exceptions into WorkflowError`() = runBlocking {
    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      then(StepWorkflow("A", "step-a"))
    }

    val typedUseCase =
      useCase.toStateUseCase<PublicInput, TestCommand, TestState, PublicOutput>(
        inputMapper = { input -> TestCommand(input.id) },
        outputMapper = { _ -> error("bad projection") },
      )

    val result = typedUseCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.ExceptionError)
    assertEquals("bad projection", (error as WorkflowError.ExceptionError).ex.message)
  }

  @Test
  fun `toEventUseCase returns projected event value`() = runBlocking {
    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      then(StepWorkflow("A", "step-a"))
      then(StepWorkflow("B", "step-b"))
    }

    val typedUseCase =
      useCase.toEventUseCase<PublicInput, TestCommand, StepEvent, PublicOutput>(
        inputMapper = { input -> TestCommand(input.id) },
        outputMapper = { event -> PublicOutput(event.step) },
      )

    val result = typedUseCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isRight())
    assertEquals("step-b", (result as Either.Right).value.value)
  }

  @Test
  fun `toEventUseCase returns raw WorkflowError when no error mapper is supplied`() = runBlocking {
    val useCase = object : UseCase<TestCommand>() {
      override suspend fun execute(command: TestCommand): Either<WorkflowError, UseCaseEvents> =
        Either.Left(WorkflowError.ValidationError("bad input"))
    }

    val typedUseCase =
      useCase.toEventUseCase<PublicInput, TestCommand, StepEvent, PublicOutput>(
        inputMapper = { input -> TestCommand(input.id) },
        outputMapper = { event -> PublicOutput(event.step) },
      )

    val result = typedUseCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    val error = (result as Either.Left).value
    assertTrue(error is WorkflowError.ValidationError)
    assertEquals("bad input", (error as WorkflowError.ValidationError).message)
  }

  @Test
  fun `toEventUseCase maps WorkflowError when error mapper is supplied`() = runBlocking {
    val useCase = object : UseCase<TestCommand>() {
      override suspend fun execute(command: TestCommand): Either<WorkflowError, UseCaseEvents> =
        Either.Left(WorkflowError.ExecutionError("event failure"))
    }

    val typedUseCase =
      useCase.toEventUseCase<PublicInput, TestCommand, StepEvent, BoundaryError, PublicOutput>(
        inputMapper = { input -> TestCommand(input.id) },
        errorMapper = { error ->
          val executionError = error as WorkflowError.ExecutionError
          BoundaryError(executionError.message)
        },
        outputMapper = { event -> PublicOutput(event.step) },
      )

    val result = typedUseCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isLeft())
    assertEquals("event failure", (result as Either.Left).value.message)
  }

  @Test
  fun `toEventUseCase mirrors executeForEvent last-matching-event semantics`() = runBlocking {
    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      then(StepWorkflow("A", "step-a"))
      then(StepWorkflow("B", "step-b"))
    }

    val typedUseCase =
      useCase.toEventUseCase<PublicInput, TestCommand, StepEvent, PublicOutput>(
        inputMapper = { input -> TestCommand(input.id) },
        outputMapper = { event -> PublicOutput(event.step) },
      )

    val result = typedUseCase(PublicInput(UUID.randomUUID()))

    assertTrue(result.isRight())
    assertEquals("step-b", (result as Either.Right).value.value)
  }

  @Test
  fun `toEventUseCase supports explicit WorkflowContext`() = runBlocking {
    val key = WorkflowContext.CORRELATION_ID

    class ContextEventWorkflow(override val id: String) : Workflow<TestState, TestState>() {
      override suspend fun executeWorkflow(
        input: TestState,
        context: WorkflowContext
      ): Either<WorkflowError, WorkflowResult<TestState>> =
        Either.Right(
          WorkflowResult(
            input,
            listOf(
              StepEvent(
                UUID.randomUUID(),
                Instant.now(),
                context.get(key) ?: "missing",
              )
            ),
            context
          )
        )
    }

    val useCase: UseCase<TestCommand> = useCase {
      startWith { command -> Either.Right(TestState(command.id)) }
      then(ContextEventWorkflow("ctx"))
    }

    val typedUseCase =
      useCase.toEventUseCase<PublicInput, TestCommand, StepEvent, PublicOutput>(
        inputMapper = { input -> TestCommand(input.id) },
        outputMapper = { event -> PublicOutput(event.step) },
      )

    val result = typedUseCase(PublicInput(UUID.randomUUID()), WorkflowContext().addData(key, "ctx-999"))

    assertTrue(result.isRight())
    assertEquals("ctx-999", (result as Either.Right).value.value)
  }
}
