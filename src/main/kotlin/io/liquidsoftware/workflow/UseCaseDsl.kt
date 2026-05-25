package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.flatMap
import kotlin.reflect.KClass

/**
 * A callable application use case.
 *
 * `UseCase` accepts public input, returns public output, and may map
 * workflow-native errors to an application-specific error type.
 */
fun interface UseCase<I, E, O> {
  /**
   * Runs the use case with explicit workflow context such as correlation IDs.
   */
  suspend operator fun invoke(input: I, context: WorkflowContext): Either<E, O>

  /**
   * Runs the use case with an empty [WorkflowContext].
   */
  suspend operator fun invoke(input: I): Either<E, O> =
    invoke(input, WorkflowContext())
}

/**
 * DSL builder for [useCase].
 */
class UseCaseBuilder<I : UseCaseCommand, O> : WorkflowChainBuilderFactory<I>() {
  private var resultProjection: UseCaseResultProjection<O>? = null

  /**
   * Defines a custom projection from the complete workflow result to public output.
   */
  fun result(projection: UseCaseResultProjection<O>) {
    resultProjection = projection
  }

  /**
   * Projects public output from the final workflow state.
   */
  fun <S : WorkflowState> result(
    source: StateResultSource<S>,
    mapper: (S) -> O,
  ) {
    resultProjection = source.toProjection(mapper)
  }

  /**
   * Projects public output from the last emitted event of the requested type.
   */
  fun <Ev : Event> result(
    source: EventResultSource<Ev>,
    mapper: (Ev) -> O,
  ) {
    resultProjection = source.toProjection(mapper)
  }

  internal fun <E> buildUseCase(
    name: String?,
    errorMapper: (WorkflowError) -> E
  ): UseCase<I, E, O> {
    val resultProjection = requireNotNull(resultProjection) { "useCase result projection is required" }
    val workflowUseCase = build(name)
    val useCaseName = workflowUseCase.name

    return UseCase { input, context ->
      workflowUseCase
        .executeProjected(input, context) { result ->
          resultProjection.project(result, useCaseName)
        }
        .mapLeft(errorMapper)
    }
  }
}

/**
 * Builds a callable application use case that preserves raw [WorkflowError] values.
 *
 * @param name Optional safe operational name used in debug-level invocation logs.
 * Do not include user data or request data in this value.
 */
fun <I : UseCaseCommand, O> useCase(
  name: String? = null,
  configure: UseCaseBuilder<I, O>.() -> Unit,
): UseCase<I, WorkflowError, O> {
  val builder = UseCaseBuilder<I, O>()
  builder.configure()
  return builder.buildUseCase(name) { error -> error }
}

/**
 * Builds a callable application use case with mapped boundary errors.
 *
 * The [errors] mapper should be total and non-throwing. It is applied to
 * workflow execution errors and result-projection errors.
 *
 * @param name Optional safe operational name used in debug-level invocation logs.
 * Do not include user data or request data in this value.
 */
fun <I : UseCaseCommand, E, O> useCase(
  name: String? = null,
  errors: (WorkflowError) -> E,
  configure: UseCaseBuilder<I, O>.() -> Unit,
): UseCase<I, E, O> {
  val builder = UseCaseBuilder<I, O>()
  builder.configure()
  return builder.buildUseCase(name, errors)
}

/**
 * Projection from a workflow-native result into public output.
 */
fun interface UseCaseResultProjection<out O> {
  fun project(result: UseCaseResult<WorkflowState>, useCaseName: String): Either<WorkflowError, O>
}

class StateResultSource<S : WorkflowState> @PublishedApi internal constructor(
  internal val type: KClass<S>,
)

class EventResultSource<Ev : Event> @PublishedApi internal constructor(
  internal val type: KClass<Ev>,
)

/**
 * Selects the final workflow state as the source for use-case output.
 */
inline fun <reified S : WorkflowState> state(): StateResultSource<S> =
  StateResultSource(S::class)

/**
 * Selects the last emitted event of this type as the source for use-case output.
 */
inline fun <reified Ev : Event> event(): EventResultSource<Ev> =
  EventResultSource(Ev::class)

private fun <S : WorkflowState, O> StateResultSource<S>.toProjection(
  mapper: (S) -> O,
): UseCaseResultProjection<O> =
  UseCaseResultProjection { result, useCaseName ->
    result
      .requireState(type, useCaseName)
      .flatMap { state -> mapProjectionOutput { mapper(state) } }
  }

private fun <Ev : Event, O> EventResultSource<Ev>.toProjection(
  mapper: (Ev) -> O,
): UseCaseResultProjection<O> =
  UseCaseResultProjection { result, useCaseName ->
    result
      .requireLastEvent(type, useCaseName)
      .flatMap { event -> mapProjectionOutput { mapper(event) } }
  }

private fun <S : WorkflowState> UseCaseResult<WorkflowState>.requireState(
  type: KClass<S>,
  useCaseName: String,
): Either<WorkflowError, S> {
  val currentState = state
  return if (type.isInstance(currentState)) {
    @Suppress("UNCHECKED_CAST")
    Either.Right(currentState as S)
  } else {
    Either.Left(
      WorkflowError.CompositionError(
        "Use case $useCaseName ended with state ${currentState::class.simpleName} instead of expected state ${type.simpleName}",
        IllegalStateException("Unexpected final state ${currentState::class.simpleName}")
      )
    )
  }
}

private fun <Ev : Event> UseCaseResult<WorkflowState>.requireLastEvent(
  type: KClass<Ev>,
  useCaseName: String,
): Either<WorkflowError, Ev> {
  val lastEvent = events.lastOrNull { event -> type.isInstance(event) }
  return if (lastEvent != null) {
    @Suppress("UNCHECKED_CAST")
    Either.Right(lastEvent as Ev)
  } else {
    Either.Left(
      WorkflowError.CompositionError(
        "Use case $useCaseName did not emit expected event ${type.simpleName}",
        IllegalStateException("Missing expected event ${type.simpleName}")
      )
    )
  }
}

@PublishedApi
internal fun <O> mapProjectionOutput(
  outputMapper: () -> O,
): Either<WorkflowError, O> =
  Either.catch(outputMapper)
    .mapLeft { WorkflowError.ExceptionError("An exception occurred", it) }
