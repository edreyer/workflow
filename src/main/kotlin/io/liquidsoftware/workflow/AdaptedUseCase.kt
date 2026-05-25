package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.flatMap
import kotlin.reflect.KClass

/**
 * A use case adapted to an application or module boundary.
 *
 * `AdaptedUseCase` accepts public input, returns public output, and can map
 * workflow-native errors to an application-specific error type. Use
 * [adaptedUseCase] when application code should call the use case directly
 * instead of working with workflow commands, states, events, and projections.
 */
fun interface AdaptedUseCase<I, E, O> {
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
 * DSL builder for [adaptedUseCase].
 */
class AdaptedUseCaseBuilder<I, C : UseCaseCommand, O> {
  private var inputMapper: ((I) -> C)? = null
  private var workflowFactory: (WorkflowChainBuilderFactory<C>.() -> Unit)? = null
  private var resultProjection: UseCaseResultProjection<O>? = null

  /**
   * Maps public input into the internal workflow command.
   */
  fun input(mapper: (I) -> C) {
    inputMapper = mapper
  }

  /**
   * Defines the workflow steps that implement this use case.
   */
  fun workflow(block: WorkflowChainBuilderFactory<C>.() -> Unit) {
    workflowFactory = block
  }

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

  internal fun <E> build(errorMapper: (WorkflowError) -> E): AdaptedUseCase<I, E, O> {
    val inputMapper = requireNotNull(inputMapper) { "adaptedUseCase input mapper is required" }
    val workflowFactory = requireNotNull(workflowFactory) { "adaptedUseCase workflow is required" }
    val resultProjection = requireNotNull(resultProjection) { "adaptedUseCase result projection is required" }
    val rawUseCase = useCase(workflowFactory)
    val useCaseName = rawUseCase::class.simpleName ?: "UseCase"

    return AdaptedUseCase { input, context ->
      mapInput(inputMapper, input)
        .flatMap { command ->
          rawUseCase.executeProjected(command, context) { result ->
            resultProjection.project(result, useCaseName)
          }
        }
        .mapLeft(errorMapper)
    }
  }
}

/**
 * Builds an application-facing use case that preserves raw [WorkflowError] values.
 *
 * Use this overload when callers are expected to handle workflow-native errors.
 * If the boundary should expose an application-specific error type, use the
 * overload with `errors = ...`.
 */
fun <I, C : UseCaseCommand, O> adaptedUseCase(
  configure: AdaptedUseCaseBuilder<I, C, O>.() -> Unit,
): AdaptedUseCase<I, WorkflowError, O> {
  val builder = AdaptedUseCaseBuilder<I, C, O>()
  builder.configure()
  return builder.build { error -> error }
}

/**
 * Builds an application-facing use case with mapped boundary errors.
 *
 * The [errors] mapper should be total and non-throwing. It is applied to
 * workflow execution errors and result-projection errors.
 */
fun <I, C : UseCaseCommand, E, O> adaptedUseCase(
  errors: (WorkflowError) -> E,
  configure: AdaptedUseCaseBuilder<I, C, O>.() -> Unit,
): AdaptedUseCase<I, E, O> {
  val builder = AdaptedUseCaseBuilder<I, C, O>()
  builder.configure()
  return builder.build(errors)
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
 * Selects the final workflow state as the source for an adapted result.
 */
inline fun <reified S : WorkflowState> state(): StateResultSource<S> =
  StateResultSource(S::class)

/**
 * Selects the last emitted event of this type as the source for an adapted result.
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
