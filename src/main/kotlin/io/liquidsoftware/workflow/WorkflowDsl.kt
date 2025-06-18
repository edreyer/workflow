package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.raise.either
import arrow.core.right
import io.liquidsoftware.workflow.WorkflowError.CompositionError
import io.liquidsoftware.workflow.WorkflowError.ExecutionError
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

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
  private var initialPropertyMap: Map<String, String> = emptyMap()
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
    propertyMap: Map<String, String> = emptyMap()
  ) {
    if (firstCalled) {
      throw IllegalStateException("first() method can only be called once")
    }
    if (otherMethodCalled) {
      throw IllegalStateException("first() method must be the first method called")
    }
    @Suppress("UNCHECKED_CAST")
    initialWorkflow = workflow as Workflow<WorkflowInput, Event>
    initialPropertyMap = propertyMap
    initialWorkflowMapper = null
    firstCalled = true
  }

  fun <WFI : WorkflowInput, E : Event> first(
    workflow: Workflow<WFI, E>,
    block: PropertyMappingBuilder.() -> Unit
  ) {
    val builder = PropertyMappingBuilder()
    builder.block()
    first(workflow, builder.build())
  }

  fun parallel(block: ParallelBlock<UCC, WorkflowInput, Event>.() -> Unit) {
    otherMethodCalled = true
    val parallelBlock = ParallelBlock<UCC, WorkflowInput, Event>()
    parallelBlock.block()
    builders.add(object : BaseWorkflowChainBuilder<UCC, WorkflowInput, Event>() {

      override fun <C : WorkflowInput, R : Event> then(
        workflow: Workflow<C, R>,
        propertyMap: Map<String, String>
      ) {
        throw UnsupportedOperationException("Cannot add workflows to a parallel block after it's created")
      }

      override fun <C : WorkflowInput, R : Event> thenIf(
        workflow: Workflow<C, R>,
        predicate: (WorkflowResult) -> Boolean,
        propertyMap: Map<String, String>
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
    propertyMap: Map<String, String> = emptyMap()
  ) {
    otherMethodCalled = true
    currentBuilder.then(workflow, propertyMap)
  }

  fun <C : WorkflowInput, R : Event> then(
    workflow: Workflow<C, R>,
    block: PropertyMappingBuilder.() -> Unit
  ) {
    otherMethodCalled = true
    val builder = PropertyMappingBuilder()
    builder.block()
    currentBuilder.then(workflow, builder.build())
  }


  fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    propertyMap: Map<String, String> = emptyMap()
  ) {
    otherMethodCalled = true
    currentBuilder.thenIf(workflow, predicate, propertyMap)
  }

  fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    block: PropertyMappingBuilder.() -> Unit
  ) {
    otherMethodCalled = true
    val builder = PropertyMappingBuilder()
    builder.block()
    currentBuilder.thenIf(workflow, predicate, builder.build())
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

        // Create an empty initial result for auto-mapping
        val emptyResult = WorkflowResult()

        val initialWorkflowInput = if (that.initialWorkflowMapper != null) {
          // Use explicit mapper if provided
          that.initialWorkflowMapper!!(ucCommand)
        } else {
          // Use auto-mapping
          // Determine the input type for the initial workflow
          @Suppress("UNCHECKED_CAST")
          val inputClass = workflow.javaClass.kotlin.supertypes[0].arguments[0].type?.classifier as? KClass<WorkflowInput>
            ?: raise(CompositionError("Cannot determine input type for initial workflow", IllegalArgumentException("Cannot determine input type")))

          // Use BaseWorkflowChainBuilder.autoMapInput (now internal)
          currentBuilder.autoMapInput(emptyResult, ucCommand, that.initialPropertyMap, inputClass)
            ?: raise(CompositionError("Cannot auto-map to ${inputClass.simpleName}", AutoMappingException("Cannot auto-map to ${inputClass.simpleName}")))
        }

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
    propertyMap: Map<String, String> = emptyMap()
  )

  abstract fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    propertyMap: Map<String, String> = emptyMap()
  )

  protected suspend fun <C : WorkflowInput, R : Event> WorkflowResult.autoMapInputAndExecuteNext(
    workflow: Workflow<C, R>,
    command: UCC,
    propertyMap: Map<String, String> = emptyMap(),
    clazz: KClass<C>
  ): Either<WorkflowError, WorkflowResult> = Either.catch {
    autoMapInput(this, command, propertyMap, clazz)
      ?: throw AutoMappingException("Cannot auto-map to ${clazz.simpleName}")
  }
    .mapLeft { ex -> CompositionError("Error mapping input: ${ex.message ?: "Unknown error"}", ex) }
    .flatMap { input -> workflow.execute(input) }

  internal fun <T : WorkflowInput> autoMapInput(
    result: WorkflowResult,
    command: UseCaseCommand,
    propertyMap: Map<String, String> = emptyMap(),
    clazz: KClass<T>
  ): T? {
    val constructor = clazz.primaryConstructor ?: return null
    val args = mutableMapOf<KParameter, Any?>()

    for (param in constructor.parameters) {
      // Check if there's a custom mapping for this parameter
      val sourcePropertyName = propertyMap[param.name] ?: param.name

      val value = when {
        // Try to find matching event property (using mapped name)
        findInEvents(result.events, sourcePropertyName, param.type.classifier as? KClass<*>) != null ->
          findInEvents(result.events, sourcePropertyName, param.type.classifier as? KClass<*>)

        // Try to find in command (using mapped name)
        findInCommand(command, sourcePropertyName) != null ->
          findInCommand(command, sourcePropertyName)

        // Try context data (using mapped name)
        result.context.data[sourcePropertyName] != null ->
          result.context.data[sourcePropertyName]

        // Check if parameter is optional
        param.isOptional -> null

        else -> return null // Cannot satisfy required parameter
      }

      if (value != null || param.isOptional) {
        args[param] = value
      }
    }

    return constructor.callBy(args)
  }

  private fun findInEvents(events: List<Event>, propertyName: String?, targetType: KClass<*>?): Any? {
    if (propertyName == null) return null
    for (event in events) {
      val eventClass = event::class
      val property = eventClass.memberProperties.find { it.name == propertyName }
      if (property != null) {
        try {
          val value = property.getter.call(event)
          if (targetType == null || targetType.isInstance(value)) {
            return value
          }
        } catch (e: Exception) {
          // Ignore property access errors
        }
      }
    }
    return null
  }

  private fun findInCommand(command: UseCaseCommand, propertyName: String?): Any? {
    if (propertyName == null) return null
    val commandClass = command::class
    val property = commandClass.memberProperties.find { it.name == propertyName }
    return try {
      property?.getter?.call(command)
    } catch (e: Exception) {
      null
    }
  }

  abstract fun build(): BuiltWorkflow<UCC, I, E>
}

internal class SequentialWorkflowChainBuilder<UCC : UseCaseCommand, I : WorkflowInput, E : Event> :
  BaseWorkflowChainBuilder<UCC, I, E>() {


  override fun <C : WorkflowInput, R : Event> then(
    workflow: Workflow<C, R>,
    propertyMap: Map<String, String>
  ) {
    workflows.add(WorkflowStep { result, context, command ->
      @Suppress("UNCHECKED_CAST")
      val clazz = workflow.javaClass.kotlin.supertypes[0].arguments[0].type?.classifier as? KClass<C>
        ?: throw IllegalArgumentException("Cannot determine input type for workflow")
      result.autoMapInputAndExecuteNext(workflow, command, propertyMap, clazz)
    })
  }

  override fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    propertyMap: Map<String, String>
  ) {
    workflows.add(WorkflowStep { result, context, command ->
      if (predicate(result)) {
        @Suppress("UNCHECKED_CAST")
        val clazz = workflow.javaClass.kotlin.supertypes[0].arguments[0].type?.classifier as? KClass<C>
          ?: throw IllegalArgumentException("Cannot determine input type for workflow")
        result.autoMapInputAndExecuteNext(workflow, command, propertyMap, clazz)
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
    propertyMap: Map<String, String>
  ) {
    workflows.add(WorkflowStep { result, context, command ->
      coroutineScope {
        @Suppress("UNCHECKED_CAST")
        val clazz = workflow.javaClass.kotlin.supertypes[0].arguments[0].type?.classifier as? KClass<C>
          ?: throw IllegalArgumentException("Cannot determine input type for workflow")
        result.autoMapInputAndExecuteNext(workflow, command, propertyMap, clazz)
      }
    })
  }

  override fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    propertyMap: Map<String, String>
  ) {
    workflows.add(WorkflowStep { result, context, command ->
      coroutineScope {
        if (predicate(result)) {
          @Suppress("UNCHECKED_CAST")
          val clazz = workflow.javaClass.kotlin.supertypes[0].arguments[0].type?.classifier as? KClass<C>
            ?: throw IllegalArgumentException("Cannot determine input type for workflow")
          result.autoMapInputAndExecuteNext(workflow, command, propertyMap, clazz)
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

class AutoMappingException(message: String) : Exception(message)

class PropertyMappingBuilder {
  private val mappings = mutableMapOf<String, String>()

  infix fun String.from(sourceName: String) {
    mappings[this] = sourceName
  }

  fun build(): Map<String, String> = mappings.toMap()
}

class ParallelBlock<UCC : UseCaseCommand, I : WorkflowInput, E : Event> internal constructor() {
  private val builder = ParallelWorkflowChainBuilder<UCC, I, E>()

  fun <C : WorkflowInput, R : Event> then(
    workflow: Workflow<C, R>,
    propertyMap: Map<String, String> = emptyMap()
  ) {
    builder.then(workflow, propertyMap)
  }

  fun <C : WorkflowInput, R : Event> then(
    workflow: Workflow<C, R>,
    block: PropertyMappingBuilder.() -> Unit
  ) {
    val mappingBuilder = PropertyMappingBuilder()
    mappingBuilder.block()
    builder.then(workflow, mappingBuilder.build())
  }

  fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    propertyMap: Map<String, String> = emptyMap()
  ) {
    builder.thenIf(workflow, predicate, propertyMap)
  }

  fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    block: PropertyMappingBuilder.() -> Unit
  ) {
    val mappingBuilder = PropertyMappingBuilder()
    mappingBuilder.block()
    builder.thenIf(workflow, predicate, mappingBuilder.build())
  }

  internal fun build() = builder.build()
}
