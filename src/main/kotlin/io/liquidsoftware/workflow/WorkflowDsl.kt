package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.raise.either
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.full.cast

/**
 * Entry point for building a UseCase with a fluent DSL.
 */
fun <C : UseCaseCommand> useCase(
  block: WorkflowChainBuilderFactory<C>.() -> Unit
): UseCase<C> {
  val factory = WorkflowChainBuilderFactory<C>()
  factory.block()
  return factory.build()
}

class WorkflowChainBuilderFactory<C : UseCaseCommand> {
  private var startWithFactory: ((C) -> Either<WorkflowError, WorkflowState>)? = null
  private var startCalled = false
  private var otherMethodCalled = false
  private val steps = mutableListOf<WorkflowStep>()

  /**
   * Initializes the pipeline state from the command/query.
   */
  fun <S : WorkflowState> startWith(factory: (C) -> Either<WorkflowError, S>) {
    if (startCalled) {
      throw IllegalStateException("startWith() can only be called once")
    }
    if (otherMethodCalled) {
      throw IllegalStateException("startWith() must be the first method called")
    }
    startWithFactory = factory
    startCalled = true
  }

  /**
   * Adds a workflow to be executed sequentially after the previous workflow.
   */
  fun <I : WorkflowState, O : WorkflowState> then(workflow: Workflow<I, O>) {
    otherMethodCalled = true
    steps.add(SequentialStep(workflow))
  }

  /**
   * Adds a workflow to be executed conditionally after the previous workflow.
   */
  fun <I : WorkflowState, O : WorkflowState> thenIf(
    workflow: Workflow<I, O>,
    predicate: (WorkflowResult<WorkflowState>) -> Boolean
  ) {
    otherMethodCalled = true
    steps.add(ConditionalStep(workflow, predicate))
  }

  /**
   * Adds a side-effect-only parallel block that preserves state.
   */
  fun <I : WorkflowState> parallel(block: ParallelSideEffectScope<I>.() -> Unit) {
    otherMethodCalled = true
    val scope = ParallelSideEffectScope<I>()
    scope.block()
    steps.add(scope.build())
  }

  fun build(): UseCase<C> {
    if (!startCalled) {
      throw IllegalStateException("startWith() must be called to set the initial state")
    }
    val startFactory = startWithFactory ?: throw IllegalStateException("startWith() not configured")
    val pipelineSteps = steps.toList()

    return object : UseCase<C>() {
      override suspend fun execute(command: C): Either<WorkflowError, UseCaseEvents> = either {
        val initialState = startFactory(command).bind()
        val initialResult = WorkflowResult(initialState)
        @Suppress("UNCHECKED_CAST")
        val start: Either<WorkflowError, WorkflowResult<WorkflowState>> =
          Either.Right(initialResult) as Either<WorkflowError, WorkflowResult<WorkflowState>>
        val finalResult = pipelineSteps.fold(start) { acc, step ->
          acc.flatMap { current -> step.execute(current) }
        }.bind()
        UseCaseEvents(finalResult.events)
      }
    }
  }
}

internal interface WorkflowStep {
  suspend fun execute(result: WorkflowResult<WorkflowState>): Either<WorkflowError, WorkflowResult<WorkflowState>>
}

private class SequentialStep<I : WorkflowState, O : WorkflowState>(
  private val workflow: Workflow<I, O>
) : WorkflowStep {
  override suspend fun execute(
    result: WorkflowResult<WorkflowState>
  ): Either<WorkflowError, WorkflowResult<WorkflowState>> {
    return castState(result.state, workflow)
      .flatMap { input ->
        workflow.execute(input).map { next -> next.mergePrevious(result) }
      }
  }
}

private class ConditionalStep<I : WorkflowState, O : WorkflowState>(
  private val workflow: Workflow<I, O>,
  private val predicate: (WorkflowResult<WorkflowState>) -> Boolean
) : WorkflowStep {
  override suspend fun execute(
    result: WorkflowResult<WorkflowState>
  ): Either<WorkflowError, WorkflowResult<WorkflowState>> {
    return if (!predicate(result)) {
      Either.Right(result)
    } else {
      castState(result.state, workflow)
        .flatMap { input ->
          workflow.execute(input).map { next -> next.mergePrevious(result) }
        }
    }
  }
}

class ParallelSideEffectScope<I : WorkflowState> internal constructor() {
  private val workflows = mutableListOf<SideEffectWorkflow<I>>()

  fun then(workflow: SideEffectWorkflow<I>) {
    workflows.add(workflow)
  }

  internal fun build(): WorkflowStep = ParallelSideEffectStep(workflows.toList())
}

private class ParallelSideEffectStep<I : WorkflowState>(
  private val workflows: List<SideEffectWorkflow<I>>
) : WorkflowStep {
  override suspend fun execute(
    result: WorkflowResult<WorkflowState>
  ): Either<WorkflowError, WorkflowResult<WorkflowState>> = coroutineScope {
    if (workflows.isEmpty()) {
      return@coroutineScope Either.Right(result)
    }

    val input = castState(result.state, workflows.first()).fold(
      { error -> return@coroutineScope Either.Left(error) },
      { value -> value }
    )
    val deferredResults = workflows.map { workflow ->
      async { workflow.execute(input) }
    }
    val results = deferredResults.awaitAll()
    val firstError = results.filterIsInstance<Either.Left<WorkflowError>>().firstOrNull()
    if (firstError != null) {
      Either.Left(firstError.value)
    } else {
      val values = results.map { (it as Either.Right<WorkflowResult<WorkflowState>>).value }
      val mergedEvents = values.flatMap { it.events }
      val mergedContext = values.fold(result.context) { acc, value -> acc.combine(value.context) }
      Either.Right(
        WorkflowResult(
          state = result.state,
          events = result.events + mergedEvents,
          context = mergedContext
        )
      )
    }
  }
}

private fun <I : WorkflowState> castState(
  state: WorkflowState,
  workflow: Workflow<I, *>
): Either<WorkflowError, I> {
  val inputClass = WorkflowUtils.getWorkflowInputClass<I>(workflow)
    ?: return Either.Left(
      WorkflowError.CompositionError(
        "Cannot determine input type for workflow",
        IllegalArgumentException("Cannot determine input type for workflow")
      )
    )

  if (!inputClass.isInstance(state)) {
    return Either.Left(
      WorkflowError.CompositionError(
        "State type ${state::class.simpleName} is not compatible with ${inputClass.simpleName}",
        IllegalArgumentException("Invalid state type for workflow")
      )
    )
  }

  return Either.Right(inputClass.cast(state))
}

/**
 * Utility functions for workflow operations.
 */
object WorkflowUtils {
  private fun resolveKotlinType(type: KType, typeVarMap: Map<KTypeParameter, KType>): KType? {
    val classifier = type.classifier
    return when (classifier) {
      is KTypeParameter -> typeVarMap[classifier]?.let { resolveKotlinType(it, typeVarMap) }
      else -> type
    }
  }

  private fun findWorkflowInputType(kclass: KClass<*>, typeVarMap: Map<KTypeParameter, KType>): KType? {
    for (supertype in kclass.supertypes) {
      when (val classifier = supertype.classifier) {
        Workflow::class -> {
          val inputType = supertype.arguments.firstOrNull()?.type ?: return null
          return resolveKotlinType(inputType, typeVarMap)
        }
        is KClass<*> -> {
          val params = classifier.typeParameters
          val args = supertype.arguments
          val nextMap = typeVarMap.toMutableMap()
          params.forEachIndexed { index, param ->
            val argType = args.getOrNull(index)?.type
            if (argType != null) {
              nextMap[param] = argType
            }
          }
          val resolved = findWorkflowInputType(classifier, nextMap)
          if (resolved != null) return resolved
        }
      }
    }
    return null
  }

  /**
   * Determines the input type class for a workflow.
   */
  @Suppress("UNCHECKED_CAST")
  fun <C : WorkflowState> getWorkflowInputClass(workflow: Workflow<*, *>): KClass<C>? {
    return getWorkflowInputClass(workflow::class) as? KClass<C>
  }

  /**
   * Determines the input type class for a workflow class (or returns null when missing).
   */
  fun getWorkflowInputClass(workflowClass: KClass<*>): KClass<out WorkflowState>? {
    val inputType = findWorkflowInputType(workflowClass, emptyMap()) ?: return null
    val classifier = inputType.classifier as? KClass<*> ?: return null
    if (!WorkflowState::class.java.isAssignableFrom(classifier.java)) return null
    @Suppress("UNCHECKED_CAST")
    return classifier as KClass<out WorkflowState>
  }
}

typealias SideEffectWorkflow<I> = Workflow<I, I>
