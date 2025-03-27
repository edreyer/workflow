package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import io.liquidsoftware.workflow.WorkflowError.CompositionError
import io.liquidsoftware.workflow.WorkflowError.ExecutionError
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// Entry point for building a UseCase
fun <UCC : UseCaseCommand> useCase(
  block: WorkflowChainBuilderFactory<UCC>.() -> Unit
): UseCase<UCC> {
  val factory = WorkflowChainBuilderFactory<UCC>()
  factory.block()
  return factory.build()
}

class WorkflowChainBuilderFactory<UCC : UseCaseCommand> {
  private var initialWorkflow: Workflow<WorkflowInput, Event>? = null
  private var initialWorkflowMapper: ((UCC) -> WorkflowInput)? = null
  private var firstCalled = false
  private var otherMethodCalled = false
  private var _command: UCC? = null

  val command: UCC
    get() = _command ?: throw IllegalStateException("Command not initialized")

  // Use WorkflowInput and Event as type parameters to satisfy the bounds
  private val builders = mutableListOf<BaseWorkflowChainBuilder<UCC, WorkflowInput, Event>>()
  private var currentBuilder = SequentialWorkflowChainBuilder<UCC, WorkflowInput, Event>()

  init {
    builders.add(currentBuilder)
  }

  fun <WFI : WorkflowInput, E : Event> first(
    workflow: Workflow<WFI, E>,
    inputMapper: (UCC) -> WFI
  ) {
    if (firstCalled) {
      throw IllegalStateException("first() method can only be called once")
    }
    if (otherMethodCalled) {
      throw IllegalStateException("first() method must be the first method called")
    }
    @Suppress("UNCHECKED_CAST")
    initialWorkflow = workflow as Workflow<WorkflowInput, Event>
    @Suppress("UNCHECKED_CAST")
    initialWorkflowMapper = inputMapper as (UCC) -> WorkflowInput
    firstCalled = true
  }

  fun parallel(block: ParallelBlock<UCC, WorkflowInput, Event>.() -> Unit) {
    otherMethodCalled = true
    val parallelBlock = ParallelBlock<UCC, WorkflowInput, Event>()
    parallelBlock.block()
    builders.add(object : BaseWorkflowChainBuilder<UCC, WorkflowInput, Event>() {
      override fun <C : WorkflowInput, R : Event> then(workflow: Workflow<C, R>, inputMapper: (WorkflowResult, UCC) -> C) {
        throw UnsupportedOperationException("Cannot add workflows to a parallel block after it's created")
      }

      override fun <C : WorkflowInput, R : Event> thenIf(
        workflow: Workflow<C, R>,
        predicate: (WorkflowResult) -> Boolean,
        inputMapper: (WorkflowResult, UCC) -> C
      ) {
        throw UnsupportedOperationException("Cannot add workflows to a parallel block after it's created")
      }

      override fun build() = parallelBlock.build()
    })
    currentBuilder = SequentialWorkflowChainBuilder<UCC, WorkflowInput, Event>()
    builders.add(currentBuilder)
  }

  fun <C : WorkflowInput, R : Event> then(
    workflow: Workflow<C, R>,
    inputMapper: (WorkflowResult, UCC) -> C
  ) {
    otherMethodCalled = true
    currentBuilder.then(workflow, inputMapper)
  }

  fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    inputMapper: (WorkflowResult, UCC) -> C
  ) {
    otherMethodCalled = true
    currentBuilder.thenIf(workflow, predicate, inputMapper)
  }

  fun build(): UseCase<UCC> {
    if (!firstCalled) {
      throw IllegalStateException("first() method must be called to set the initial workflow")
    }

    val that = this
    return object : UseCase<UCC>() {
      override suspend fun execute(ucCommand: UCC): Either<WorkflowError, WorkflowResult> = either {
        that._command = ucCommand
        val workflow = that.initialWorkflow ?: raise(CompositionError("Initial workflow not set", IllegalStateException("Initial workflow not set")))
        val mapper = that.initialWorkflowMapper ?: raise(CompositionError("Initial workflow mapper not set", IllegalStateException("Initial workflow mapper not set")))

        val initialWorkflowInput = mapper(ucCommand)
        val initialResult = workflow.execute(initialWorkflowInput)

        return initialResult
          .mapLeft { ExecutionError("No workflows found") }
          .flatMap { result ->
            either {
              builders.fold(result.right() as Either<WorkflowError, WorkflowResult>) { workflowResult, builder ->
                val builtWorkflow = builder.build()
                builtWorkflow.execute(initialWorkflowInput, workflowResult.bind(), ucCommand)
              }.bind()
            }
          }
      }
    }
  }
}

internal abstract class BuiltWorkflow<UCC : UseCaseCommand, I : WorkflowInput, E : Event> {
  abstract suspend fun execute(input: I, result: WorkflowResult, command: UCC): Either<WorkflowError, WorkflowResult>
}

internal class WorkflowStep<UCC : UseCaseCommand, I : WorkflowInput, E : Event>(
  val step: suspend (WorkflowResult, WorkflowContext, UCC) -> Either<WorkflowError, WorkflowResult>
)

internal abstract class BaseWorkflowChainBuilder<UCC : UseCaseCommand, I : WorkflowInput, E : Event> {
  protected val workflows = mutableListOf<WorkflowStep<UCC, I, E>>()

  abstract fun <C : WorkflowInput, R : Event> then(
    workflow: Workflow<C, R>,
    inputMapper: (WorkflowResult, UCC) -> C
  )

  abstract fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    inputMapper: (WorkflowResult, UCC) -> C
  )

  protected suspend fun <C : WorkflowInput, R : Event> WorkflowResult.mapInputAndExecuteNext(
    inputMapper: (WorkflowResult, UCC) -> C,
    workflow: Workflow<C, R>,
    command: UCC
  ): Either<WorkflowError, WorkflowResult> = Either.catch {
    inputMapper(this, command)
  }
    .mapLeft { ex -> CompositionError("Error mapping input: ${ex.message ?: "Unknown error"}", ex) }
    .flatMap { input -> workflow.execute(input) }

  abstract fun build(): BuiltWorkflow<UCC, I, E>
}

internal class SequentialWorkflowChainBuilder<UCC : UseCaseCommand, I : WorkflowInput, E : Event> :
  BaseWorkflowChainBuilder<UCC, I, E>() {

  override fun <C : WorkflowInput, R : Event> then(
    workflow: Workflow<C, R>,
    inputMapper: (WorkflowResult, UCC) -> C
  ) {
    workflows.add(WorkflowStep { result, context, command ->
      result.mapInputAndExecuteNext(inputMapper, workflow, command)
    })
  }

  override fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    inputMapper: (WorkflowResult, UCC) -> C
  ) {
    workflows.add(WorkflowStep { result, context, command ->
      if (predicate(result)) {
        result.mapInputAndExecuteNext(inputMapper, workflow, command)
      } else {
        Either.Right(WorkflowResult())
      }
    })
  }

  override fun build(): BuiltWorkflow<UCC, I, E> {
    return object : BuiltWorkflow<UCC, I, E>() {
      override suspend fun execute(input: I, result: WorkflowResult, command: UCC): Either<WorkflowError, WorkflowResult> {
        var currentResult: Either<WorkflowError, WorkflowResult> = result.right()
        for (workflow in workflows) {
          if (currentResult.isRight()) {
            val result = (currentResult as Either.Right).value
            val nextResult = workflow.step(result, result.context, command)
            currentResult = nextResult.fold(
              { currentResult },
              { workflowResult -> workflowResult.combine(result).right() }
            )
          } else {
            break
          }
        }

        return currentResult
      }
    }
  }
}

internal class ParallelWorkflowChainBuilder<UCC : UseCaseCommand, I : WorkflowInput, E : Event> :
  BaseWorkflowChainBuilder<UCC, I, E>() {

  override fun <C : WorkflowInput, R : Event> then(
    workflow: Workflow<C, R>,
    inputMapper: (WorkflowResult, UCC) -> C
  ) {
    workflows.add(WorkflowStep { result, context, command ->
      coroutineScope {
        result.mapInputAndExecuteNext(inputMapper, workflow, command)
      }
    })
  }

  override fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    inputMapper: (WorkflowResult, UCC) -> C
  ) {
    workflows.add(WorkflowStep { result, context, command ->
      coroutineScope {
        if (predicate(result)) {
          result.mapInputAndExecuteNext(inputMapper, workflow, command)
        } else {
          WorkflowResult(emptyList(), context).right()
        }
      }
    })
  }

  override fun build(): BuiltWorkflow<UCC, I, E> {
    return object : BuiltWorkflow<UCC, I, E>() {
      override suspend fun execute(input: I, result: WorkflowResult, command: UCC): Either<WorkflowError, WorkflowResult> {
        val deferredResults = coroutineScope {
          workflows.map { workflow ->
            async { workflow.step(result, result.context, command) }
          }
        }
        val results = deferredResults.awaitAll()

        val newExecutions = results.fold(result.context.executions) { acc, newResult ->
          acc + newResult.fold({ emptyList() }, { listOf(it.context.executions.last()) })
        }

        val combinedResult = results.fold(result.right() as Either<WorkflowError, WorkflowResult>) { acc, newResult ->
          acc.flatMap { accResult ->
            newResult.map { it.combine(accResult) }
          }
        }.map { it.copy(context = it.context.copy(executions = newExecutions)) }

        return combinedResult
      }
    }
  }
}

class ParallelBlock<UCC : UseCaseCommand, I : WorkflowInput, E : Event> internal constructor() {
  private val builder = ParallelWorkflowChainBuilder<UCC, I, E>()

  fun <C : WorkflowInput, R : Event> then(
    workflow: Workflow<C, R>,
    inputMapper: (WorkflowResult, UCC) -> C
  ) {
    builder.then(workflow, inputMapper)
  }

  fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    inputMapper: (WorkflowResult, UCC) -> C
  ) {
    builder.thenIf(workflow, predicate, inputMapper)
  }

  internal fun build() = builder.build()
}
