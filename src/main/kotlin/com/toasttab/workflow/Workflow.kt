package com.toasttab.workflow

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import com.toasttab.workflow.WorkflowError.ExecutionError
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

sealed interface Input
interface Command : Input
interface Query : Input

interface Event {
    val id: UUID
    val timestamp: Instant
}

sealed class WorkflowError {
    data class ValidationError(val message: String) : WorkflowError()
    data class ExecutionError(val message: String) : WorkflowError()
}

data class WorkflowResult(
    val context: Context,
    val events: List<Event>) {

    fun combine(other: WorkflowResult): WorkflowResult {
        val combinedContext = this.context.combine(other.context)
        val combinedEvents = this.events + other.events
        return WorkflowResult(combinedContext, combinedEvents)
    }
}

data class WorkflowExecution(
    val workflowName: String,
    val startTime: Instant,
    val endTime: Instant,
    val succeeded: Boolean
)

data class Context(
    val data: Map<String, Any> = emptyMap(),
    val executions: List<WorkflowExecution> = emptyList()
) {
    fun addData(key: String, value: Any): Context {
        val newData = data + (key to value)
        return copy(data = newData)
    }

    fun getData(key: String): Any? {
        return data[key]
    }

    fun combine(other: Context): Context {
        val combinedData = data + other.data
        return copy(data = combinedData)
    }

    fun addExecution(execution: WorkflowExecution): Context {
        val newExecutions = executions + execution
        return copy(executions = newExecutions)
    }

}

// Base Workflow interface
abstract class Workflow<I : Input, E: Event> {

    protected abstract suspend fun executeWorkflow(input: I, context: Context): Either<WorkflowError, WorkflowResult>

    suspend fun execute(input: I, context: Context): Either<WorkflowError, WorkflowResult> {
        val startTime = Instant.now()
        val result = executeWorkflow(input, context)
        val endTime = Instant.now()

        val execution = WorkflowExecution(
            workflowName = this::class.simpleName ?: "UnknownWorkflow",
            startTime = startTime,
            endTime = endTime,
            succeeded = result.isRight()
        )

        val updatedContext = result.fold(
            { context.addExecution(execution)},
            { wr -> wr.context.addExecution(execution) }
        )

        return result.map { it.copy(context = updatedContext) }
    }
}

internal abstract class BuiltWorkflow<I : Input, E : Event> {
    abstract suspend fun execute(input: I, result: WorkflowResult): Either<WorkflowError, WorkflowResult>
}

abstract class UseCase<I : Input, E : Event> {
    abstract suspend fun execute(input: I): Either<WorkflowError, WorkflowResult>
}

sealed class WorkflowStep<I : Input, E : Event> {
    data class Sync<I : Input, E : Event>(
        val step: suspend (WorkflowResult, Context) -> Either<WorkflowError, WorkflowResult>
    ) : WorkflowStep<I, E>()

    data class Async<I : Input, E : Event>(
        val step: suspend (WorkflowResult, Context) -> Deferred<Either<WorkflowError, WorkflowResult>>
    ) : WorkflowStep<I, E>()
}

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

    abstract fun build(): BuiltWorkflow<I, E>
}

internal class SequentialWorkflowChainBuilder<I : Input, E : Event> : BaseWorkflowChainBuilder<I, E>() {

    override fun <C : Input, R : Event> then(
        workflow: Workflow<C, R>,
        inputMapper: (WorkflowResult) -> C
    ) {
        workflows.add(WorkflowStep.Sync { result, context ->
            workflow.execute(inputMapper(result), context)
        })
    }

    override fun <C : Input, R : Event> thenIf(
        workflow: Workflow<C, R>,
        predicate: (WorkflowResult) -> Boolean,
        inputMapper: (WorkflowResult) -> C
    ) {
        workflows.add(WorkflowStep.Sync { result, context ->
            if (predicate(result)) {
                workflow.execute(inputMapper(result), context)
            } else {
                Either.Right(WorkflowResult(context, emptyList()))
            }
        })
    }

    override fun build(): BuiltWorkflow<I, E> {
        return object : BuiltWorkflow<I, E>() {
            override suspend fun execute(input: I, result: WorkflowResult): Either<WorkflowError, WorkflowResult> {
                var currentResult: Either<WorkflowError, WorkflowResult> = result.right()
                var currentContext = result.context

                for (workflow in workflows) {
                    if (currentResult.isRight()) {
                        val result = (currentResult as Either.Right).value
                        val nextResult = when (workflow) {
                            is WorkflowStep.Sync -> workflow.step(result, currentContext)
                            is WorkflowStep.Async -> workflow.step(result, currentContext).await()
                        }
                        currentResult = nextResult.fold(
                            { currentResult },
                            { workflowResult -> workflowResult.combine(result).right() }
                        )
                        currentContext = currentResult.fold({ result.context }, { it.context })
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
        workflows.add(WorkflowStep.Async { result, context ->
            coroutineScope {
                async { workflow.execute(inputMapper(result), context) }
            }
        })
    }

    override fun <C : Input, R : Event> thenIf(
        workflow: Workflow<C, R>,
        predicate: (WorkflowResult) -> Boolean,
        inputMapper: (WorkflowResult) -> C
    ) {
        workflows.add(WorkflowStep.Async { result, context ->
            coroutineScope {
                if (predicate(result)) {
                    async { workflow.execute(inputMapper(result), context) }
                } else {
                    async { WorkflowResult(context, emptyList()).right() }
                }
            }
        })
    }

    override fun build(): BuiltWorkflow<I, E> {
        return object : BuiltWorkflow<I, E>() {
            override suspend fun execute(input: I, result: WorkflowResult): Either<WorkflowError, WorkflowResult> {
                val deferredResults = coroutineScope {
                    workflows.map { workflow ->
                        when (workflow) {
                            is WorkflowStep.Sync -> async { workflow.step(result, result.context) }
                            is WorkflowStep.Async -> workflow.step(result, result.context)
                        }
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

    fun build(): UseCase<I, E> {
        return object : UseCase<I, E>() {
            override suspend fun execute(input: I): Either<WorkflowError, WorkflowResult> {
                val context = Context()
                var currentResult: Either<WorkflowError, WorkflowResult> = initialWorkflow.execute(input, context)
                return currentResult.fold(
                    { ExecutionError("No workflows found").left() },
                    { result ->
                        for (builder in builders) {
                            val builtWorkflow = builder.build()
                            currentResult = builtWorkflow.execute(input, result)
                        }
                        currentResult
                    }
                )
            }
        }
    }
}

fun <I : Input, E : Event> useCase(
    initialWorkflow: Workflow<I, E>,
    block: WorkflowChainBuilderFactory<I, E>.() -> Unit
): UseCase<I, E> {
    val factory = WorkflowChainBuilderFactory(initialWorkflow)
    factory.block()
    return factory.build()
}
