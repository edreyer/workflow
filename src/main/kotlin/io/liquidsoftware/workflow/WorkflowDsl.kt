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
fun <I : Input, E : Event> useCase(
    initialWorkflow: Workflow<I, E>,
    block: WorkflowChainBuilderFactory<I, E>.() -> Unit
): UseCase<I> {
    val factory = WorkflowChainBuilderFactory(initialWorkflow)
    factory.block()
    return factory.build()
}

internal abstract class BuiltWorkflow<I : Input, E : Event> {
    abstract suspend fun execute(input: I, result: WorkflowResult): Either<WorkflowError, WorkflowResult>
}

internal class WorkflowStep<I : Input, E : Event>(
    val step: suspend (WorkflowResult, WorkflowContext) -> Either<WorkflowError, WorkflowResult>
)

internal abstract class BaseWorkflowChainBuilder<I : Input, E : Event> {
    protected val workflows = mutableListOf<WorkflowStep<I, E>>()

    abstract fun <C : Input, R : Event> then(
        workflow: Workflow<C, R>,
        inputMapper: (WorkflowResult) -> C
    )

    abstract fun <C : Input, R : Event> thenIf(
        workflow: Workflow<C, R>,
        predicate: (WorkflowResult) -> Boolean,
        inputMapper: (WorkflowResult) -> C
    )

    protected suspend fun <C : Input, R : Event> WorkflowResult.mapInputAndExecuteNext(
        inputMapper: (WorkflowResult) -> C,
        workflow: Workflow<C, R>,
    ): Either<WorkflowError, WorkflowResult> = Either.catch {
        inputMapper(this)
    }
        .mapLeft { ex -> CompositionError("Error mapping input: ${ex.message ?: "Unknown error"}", ex) }
        .flatMap { input -> workflow.execute(input) }

    abstract fun build(): BuiltWorkflow<I, E>
}

internal class SequentialWorkflowChainBuilder<I : Input, E : Event> : BaseWorkflowChainBuilder<I, E>() {

    override fun <C : Input, R : Event> then(
        workflow: Workflow<C, R>,
        inputMapper: (WorkflowResult) -> C
    ) {
        workflows.add(WorkflowStep { result, context ->
            result.mapInputAndExecuteNext(inputMapper, workflow)
        })
    }

    override fun <C : Input, R : Event> thenIf(
        workflow: Workflow<C, R>,
        predicate: (WorkflowResult) -> Boolean,
        inputMapper: (WorkflowResult) -> C
    ) {
        workflows.add(WorkflowStep { result, context ->
            if (predicate(result)) {
                result.mapInputAndExecuteNext(inputMapper, workflow)
            } else {
                Either.Right(WorkflowResult())
            }
        })
    }

    override fun build(): BuiltWorkflow<I, E> {
        return object : BuiltWorkflow<I, E>() {
            override suspend fun execute(input: I, result: WorkflowResult): Either<WorkflowError, WorkflowResult> {
                var currentResult: Either<WorkflowError, WorkflowResult> = result.right()
                for (workflow in workflows) {
                    if (currentResult.isRight()) {
                        val result = (currentResult as Either.Right).value
                        val nextResult = workflow.step(result, result.context)
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

internal class ParallelWorkflowChainBuilder<I : Input, E : Event> : BaseWorkflowChainBuilder<I, E>() {

    override fun <C : Input, R : Event> then(
        workflow: Workflow<C, R>,
        inputMapper: (WorkflowResult) -> C
    ) {
        workflows.add(WorkflowStep { result, context ->
            coroutineScope {
                result.mapInputAndExecuteNext(inputMapper, workflow)
            }
        })
    }

    override fun <C : Input, R : Event> thenIf(
        workflow: Workflow<C, R>,
        predicate: (WorkflowResult) -> Boolean,
        inputMapper: (WorkflowResult) -> C
    ) {
        workflows.add(WorkflowStep { result, context ->
            coroutineScope {
                if (predicate(result)) {
                    result.mapInputAndExecuteNext(inputMapper, workflow)
                } else {
                    WorkflowResult(emptyList(), context).right()
                }
            }
        })
    }

    override fun build(): BuiltWorkflow<I, E> {
        return object : BuiltWorkflow<I, E>() {
            override suspend fun execute(input: I, result: WorkflowResult): Either<WorkflowError, WorkflowResult> {
                val deferredResults = coroutineScope {
                    workflows.map { workflow ->
                        async { workflow.step(result, result.context) }
                    }
                }
                val results = deferredResults.awaitAll()

                // collect executions. We want the last execution from each result
                val newExecutions = results.fold(result.context.executions) { acc, newResult ->
                    acc + newResult.fold({ emptyList() }, { listOf(it.context.executions.last()) })
                }

                // combine contexts and events and add the executions
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

class WorkflowChainBuilderFactory<I : Input, E : Event>(
    private val initialWorkflow: Workflow<I, E>
) {
    private var runParallel = false
    private val builders = mutableListOf<BaseWorkflowChainBuilder<I, E>>()
    private var currentBuilder: BaseWorkflowChainBuilder<I, E> = SequentialWorkflowChainBuilder()

    init {
        builders.add(currentBuilder)
    }

    fun runParallel(runParallel: Boolean) {
        this.runParallel = runParallel
        currentBuilder = if (runParallel) {
            ParallelWorkflowChainBuilder()
        } else {
            SequentialWorkflowChainBuilder()
        }
        builders.add(currentBuilder)
    }

    fun <C : Input, R : Event> then(
        workflow: Workflow<C, R>,
        inputMapper: (WorkflowResult) -> C
    ) {
        currentBuilder.then(workflow, inputMapper)
    }

    fun <C : Input, R : Event> thenIf(
        workflow: Workflow<C, R>,
        predicate: (WorkflowResult) -> Boolean,
        inputMapper: (WorkflowResult) -> C
    ) {
        currentBuilder.thenIf(workflow, predicate, inputMapper)
    }

    fun build(): UseCase<I> {
        return object : UseCase<I>() {
            override suspend fun execute(input: I): Either<WorkflowError, WorkflowResult> {
                var initialResult: Either<WorkflowError, WorkflowResult> = initialWorkflow.execute(input)
                return initialResult.fold(
                    { ExecutionError("No workflows found").left() },
                    { result -> either<WorkflowError, WorkflowResult> {
                        builders.fold(result.right() as Either<WorkflowError, WorkflowResult>) { workflowResult, builder ->
                            val builtWorkflow = builder.build()
                            builtWorkflow.execute(input, workflowResult.bind())
                        }.bind()
                    }
                    }
                )
            }
        }
    }
}

