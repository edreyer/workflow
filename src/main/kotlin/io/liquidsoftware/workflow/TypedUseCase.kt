package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.flatMap

fun interface TypedUseCase<I, E, O> {
  suspend operator fun invoke(input: I): Either<E, O>
}

fun interface ContextualTypedUseCase<I, E, O> : TypedUseCase<I, E, O> {
  suspend operator fun invoke(input: I, context: WorkflowContext): Either<E, O>

  override suspend fun invoke(input: I): Either<E, O> =
    invoke(input, WorkflowContext())
}

inline fun <I, C : UseCaseCommand, reified S : WorkflowState, O> UseCase<C>.toStateUseCase(
  noinline inputMapper: (I) -> C,
  crossinline outputMapper: (S) -> O,
): ContextualTypedUseCase<I, WorkflowError, O> =
  toTypedUseCaseInternal(
    inputMapper = inputMapper,
    errorMapper = ::identityWorkflowError,
  ) { result, useCaseName ->
    result
      .requireState<S>(useCaseName)
      .flatMap { state -> mapProjectionOutput { outputMapper(state) } }
  }

inline fun <I, C : UseCaseCommand, reified S : WorkflowState, E, O> UseCase<C>.toStateUseCase(
  noinline inputMapper: (I) -> C,
  noinline errorMapper: (WorkflowError) -> E,
  crossinline outputMapper: (S) -> O,
): ContextualTypedUseCase<I, E, O> =
  toTypedUseCaseInternal(
    inputMapper = inputMapper,
    errorMapper = errorMapper,
  ) { result, useCaseName ->
    result
      .requireState<S>(useCaseName)
      .flatMap { state -> mapProjectionOutput { outputMapper(state) } }
  }

inline fun <I, C : UseCaseCommand, reified Ev : Event, O> UseCase<C>.toEventUseCase(
  noinline inputMapper: (I) -> C,
  crossinline outputMapper: (Ev) -> O,
): ContextualTypedUseCase<I, WorkflowError, O> =
  toTypedUseCaseInternal(
    inputMapper = inputMapper,
    errorMapper = ::identityWorkflowError,
  ) { result, useCaseName ->
    result
      .requireLastEvent<Ev>(useCaseName)
      .flatMap { event -> mapProjectionOutput { outputMapper(event) } }
  }

inline fun <I, C : UseCaseCommand, reified Ev : Event, E, O> UseCase<C>.toEventUseCase(
  noinline inputMapper: (I) -> C,
  noinline errorMapper: (WorkflowError) -> E,
  crossinline outputMapper: (Ev) -> O,
): ContextualTypedUseCase<I, E, O> =
  toTypedUseCaseInternal(
    inputMapper = inputMapper,
    errorMapper = errorMapper,
  ) { result, useCaseName ->
    result
      .requireLastEvent<Ev>(useCaseName)
      .flatMap { event -> mapProjectionOutput { outputMapper(event) } }
  }

@PublishedApi
internal fun <I, C : UseCaseCommand, E, O> UseCase<C>.toTypedUseCaseInternal(
  inputMapper: (I) -> C,
  errorMapper: (WorkflowError) -> E,
  projector: (UseCaseResult<WorkflowState>, String) -> Either<WorkflowError, O>,
): ContextualTypedUseCase<I, E, O> {
  val useCaseName = this::class.simpleName ?: "UseCase"

  return ContextualTypedUseCase { input, context ->
    mapInput(inputMapper, input)
      .flatMap { command ->
        executeProjected(command, context) { result ->
          projector(result, useCaseName)
        }
      }
      .mapLeft(errorMapper)
  }
}

@PublishedApi
internal fun identityWorkflowError(error: WorkflowError): WorkflowError = error

@PublishedApi
internal fun <I, C : UseCaseCommand> mapInput(
  inputMapper: (I) -> C,
  input: I,
): Either<WorkflowError, C> =
  Either.catch { inputMapper(input) }
    .mapLeft { WorkflowError.ExceptionError("An exception occurred", it) }

@PublishedApi
internal fun <O> mapProjectionOutput(
  outputMapper: () -> O,
): Either<WorkflowError, O> =
  Either.catch(outputMapper)
    .mapLeft { WorkflowError.ExceptionError("An exception occurred", it) }
