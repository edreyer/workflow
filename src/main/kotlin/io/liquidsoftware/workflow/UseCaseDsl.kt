package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.flatMap
import java.time.Duration
import java.time.Instant
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
class UseCaseBuilder<I : UseCaseCommand, E, O> internal constructor() : WorkflowChainBuilderFactory<I>() {
  private var resultProjection: UseCaseResultProjection<O>? = null
  private val loggingBuilder = UseCaseLoggingBuilder<E>()

  /**
   * Defines a custom projection from the complete workflow result to public output.
   */
  fun result(projection: UseCaseResultProjection<O>) {
    setResultProjection(projection)
  }

  /**
   * Projects public output from the final workflow state.
   */
  fun <S : WorkflowState> result(
    source: StateResultSource<S>,
    mapper: (S) -> O,
  ) {
    setResultProjection(source.toProjection(mapper))
  }

  /**
   * Projects public output from the last emitted event of the requested type.
   */
  fun <Ev : Event> result(
    source: EventResultSource<Ev>,
    mapper: (Ev) -> O,
  ) {
    setResultProjection(source.toProjection(mapper))
  }

  fun logging(configure: UseCaseLoggingBuilder<E>.() -> Unit) {
    loggingBuilder.configure()
  }

  private fun setResultProjection(projection: UseCaseResultProjection<O>) {
    check(resultProjection == null) { "result() can only be called once" }
    resultProjection = projection
  }

  internal fun buildUseCase(
    name: String?,
    errorMapper: (WorkflowError) -> E
  ): UseCase<I, E, O> {
    val resultProjection = requireNotNull(resultProjection) { "useCase result projection is required" }
    val loggingDefinition = loggingBuilder.build()
    val workflowUseCase = build(name)
    val useCaseName = workflowUseCase.name

    return UseCase { input, context ->
      val startTime = Instant.now()
      logUseCaseStarted(useCaseName)
      val detailed = workflowUseCase.executeWithoutLogging(input, context)
      val projected =
        Either.catch {
          detailed.flatMap { result ->
            resultProjection.project(result, useCaseName)
          }
        }
          .mapLeft { WorkflowError.ExceptionError("An exception occurred", it) }
          .flatMap { it }

      val finalResult = projected.mapLeft { error ->
        try {
          errorMapper(error)
        } catch (throwable: Throwable) {
          val duration = Duration.between(startTime, Instant.now())
          logUseCaseFinished(useCaseName, succeeded = false, duration, detailed.logMetadata())
          throw throwable
        }
      }
      val duration = Duration.between(startTime, Instant.now())
      logUseCaseFinished(
        useCaseName,
        finalResult.isRight(),
        duration,
        detailed.logMetadata(),
      ) {
        loggingDefinition.fieldsFor(useCaseName, detailed, finalResult)
      }
      finalResult
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
  configure: UseCaseBuilder<I, WorkflowError, O>.() -> Unit,
): UseCase<I, WorkflowError, O> {
  val builder = UseCaseBuilder<I, WorkflowError, O>()
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
  configure: UseCaseBuilder<I, E, O>.() -> Unit,
): UseCase<I, E, O> {
  val builder = UseCaseBuilder<I, E, O>()
  builder.configure()
  return builder.buildUseCase(name, errors)
}

/**
 * Projection from a workflow-native result into public output.
 */
fun interface UseCaseResultProjection<out O> {
  fun project(result: UseCaseResult<WorkflowState>, useCaseName: String): Either<WorkflowError, O>
}

@WorkflowDsl
class UseCaseLoggingBuilder<E> internal constructor() {
  private val successProjections = mutableListOf<UseCaseSuccessLogProjection>()
  private var failureProjection: UseCaseFailureLogProjection<E>? = null

  fun <S : WorkflowState> success(
    source: StateResultSource<S>,
    fields: UseCaseLogFieldCollector.(S) -> Unit,
  ) {
    successProjections += source.toSuccessLogProjection(fields)
  }

  fun <Ev : Event> success(
    source: EventResultSource<Ev>,
    fields: UseCaseLogFieldCollector.(Ev) -> Unit,
  ) {
    successProjections += source.toSuccessLogProjection(fields)
  }

  fun failure(fields: UseCaseLogFieldCollector.(E) -> Unit) {
    check(failureProjection == null) { "failure logging can only be configured once" }
    failureProjection = UseCaseFailureLogProjection(fields)
  }

  internal fun build(): UseCaseLoggingDefinition<E> =
    UseCaseLoggingDefinition(
      successProjections = successProjections.toList(),
      failureProjection = failureProjection,
    )
}

internal data class UseCaseLoggingDefinition<E>(
  val successProjections: List<UseCaseSuccessLogProjection>,
  val failureProjection: UseCaseFailureLogProjection<E>?,
)

internal fun <E, O> UseCaseLoggingDefinition<E>.fieldsFor(
  useCaseName: String,
  detailed: Either<WorkflowError, UseCaseResult<WorkflowState>>,
  finalResult: Either<E, O>,
): List<UseCaseLogField> =
  when (finalResult) {
    is Either.Right -> detailed.fold(
      { emptyList() },
      { result ->
        successProjections.flatMap { projection ->
          projection.fields(result, useCaseName).fold(
            { error ->
              logUseCaseProjectionFailure(
                useCaseName = useCaseName,
                projection = error::class.simpleName ?: "WorkflowError",
                error = IllegalStateException(error.logMessage),
              )
              emptyList()
            },
            { fields -> fields }
          )
        }
      }
    )
    is Either.Left -> failureProjection?.fields(finalResult.value)?.fold(
      { error ->
        logUseCaseProjectionFailure(
          useCaseName = useCaseName,
          projection = error::class.simpleName ?: "WorkflowError",
          error = IllegalStateException(error.logMessage),
        )
        emptyList()
      },
      { fields -> fields }
    ).orEmpty()
  }

private val WorkflowError.logMessage: String
  get() = when (this) {
    is WorkflowError.ValidationError -> message
    is WorkflowError.ExecutionError -> message
    is WorkflowError.DomainError -> message
    is WorkflowError.ExceptionError -> message
    is WorkflowError.CompositionError -> message
    is WorkflowError.ExecutionContextError -> error.logMessage
    is WorkflowError.ChainError -> error.logMessage
  }

internal fun interface UseCaseSuccessLogProjection {
  fun fields(result: UseCaseResult<WorkflowState>, useCaseName: String): Either<WorkflowError, List<UseCaseLogField>>
}

internal class UseCaseFailureLogProjection<E>(
  private val collectFields: UseCaseLogFieldCollector.(E) -> Unit,
) {
  fun fields(error: E): Either<WorkflowError, List<UseCaseLogField>> =
    mapLoggingFields { collectUseCaseLogFields { collectFields(error) } }
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

private fun <S : WorkflowState> StateResultSource<S>.toSuccessLogProjection(
  fields: UseCaseLogFieldCollector.(S) -> Unit,
): UseCaseSuccessLogProjection =
  UseCaseSuccessLogProjection { result, useCaseName ->
    result
      .requireState(type, useCaseName)
      .flatMap { state -> mapLoggingFields { collectUseCaseLogFields { fields(state) } } }
  }

private fun <Ev : Event> EventResultSource<Ev>.toSuccessLogProjection(
  fields: UseCaseLogFieldCollector.(Ev) -> Unit,
): UseCaseSuccessLogProjection =
  UseCaseSuccessLogProjection { result, useCaseName ->
    result
      .requireLastEvent(type, useCaseName)
      .flatMap { event -> mapLoggingFields { collectUseCaseLogFields { fields(event) } } }
  }

private fun mapLoggingFields(
  block: () -> List<UseCaseLogField>,
): Either<WorkflowError, List<UseCaseLogField>> =
  Either.catch(block)
    .mapLeft { WorkflowError.ExceptionError("An exception occurred", it) }

@PublishedApi
internal fun <O> mapProjectionOutput(
  outputMapper: () -> O,
): Either<WorkflowError, O> =
  Either.catch(outputMapper)
    .mapLeft { WorkflowError.ExceptionError("An exception occurred", it) }
