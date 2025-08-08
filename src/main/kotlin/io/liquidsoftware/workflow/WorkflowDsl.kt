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

/**
 * Entry point for building a UseCase with a fluent DSL
 *
 * @param block The DSL configuration block
 * @return A configured UseCase ready for execution
 */
fun <UCC : UseCaseCommand> useCase(
  block: WorkflowChainBuilderFactory<UCC>.() -> Unit
): UseCase<UCC> {
  val factory = WorkflowChainBuilderFactory<UCC>()
  factory.block()
  return factory.build()
}

/**
 * Factory for building workflow chains using a fluent DSL
 *
 * This class is responsible for constructing a workflow chain by configuring
 * the initial workflow and subsequent workflows in the chain.
 */
class WorkflowChainBuilderFactory<UCC : UseCaseCommand> {
  private var initialWorkflow: Workflow<WorkflowInput, Event>? = null
  private var initialWorkflowMapper: ((UCC) -> WorkflowInput)? = null
  private var initialPropertyMap: Map<String, String> = emptyMap()
  private var firstCalled = false
  private var otherMethodCalled = false
  private var _command: UCC? = null
  private val interceptors = mutableListOf<WorkflowInterceptor>()

  /**
   * Access to the current command being processed
   * @throws IllegalStateException if accessed before command is initialized
   */
  val command: UCC
    get() = _command ?: throw IllegalStateException("Command not initialized")

  fun interceptor(interceptor: WorkflowInterceptor) {
    interceptors.add(interceptor)
  }

  // Collection of workflow chain builders
  private val builders = mutableListOf<BaseWorkflowChainBuilder<UCC, WorkflowInput, Event>>()
  private var currentBuilder = SequentialWorkflowChainBuilder<UCC, WorkflowInput, Event>()

  init {
    builders.add(currentBuilder)
  }


  /**
   * Sets the initial workflow in the chain with a property map for input mapping
   *
   * This method must be called exactly once and must be the first method called
   * in the DSL configuration block.
   *
   * @param workflow The initial workflow to execute
   * @param propertyMap Map of target property names to source property names for input mapping
   * @throws IllegalStateException if called more than once or after other methods
   */
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

  /**
   * Sets the initial workflow in the chain with a property mapping builder for input mapping
   *
   * This method must be called exactly once and must be the first method called
   * in the DSL configuration block.
   *
   * @param workflow The initial workflow to execute
   * @param block Property mapping configuration block
   * @throws IllegalStateException if called more than once or after other methods
   */
  fun <WFI : WorkflowInput, E : Event> first(
    workflow: Workflow<WFI, E>,
    block: PropertyMappingBuilder.() -> Unit
  ) {
    first(workflow, buildPropertyMap(block))
  }

  /**
   * Creates a parallel execution block for workflows
   *
   * Workflows inside the parallel block will be executed concurrently.
   *
   * @param block Configuration block for parallel workflows
   */
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

  /**
   * Adds a workflow to be executed sequentially after the previous workflow
   *
   * @param workflow The workflow to execute
   * @param propertyMap Map of target property names to source property names for input mapping
   */
  fun <C : WorkflowInput, R : Event> then(
    workflow: Workflow<C, R>,
    propertyMap: Map<String, String> = emptyMap()
  ) {
    otherMethodCalled = true
    currentBuilder.then(workflow, propertyMap)
  }

  /**
   * Adds a workflow to be executed sequentially after the previous workflow
   * with a property mapping builder for input mapping
   *
   * @param workflow The workflow to execute
   * @param block Property mapping configuration block
   */
  fun <C : WorkflowInput, R : Event> then(
    workflow: Workflow<C, R>,
    block: PropertyMappingBuilder.() -> Unit
  ) {
    otherMethodCalled = true
    currentBuilder.then(workflow, buildPropertyMap(block))
  }

  /**
   * Adds a workflow to be executed conditionally after the previous workflow
   *
   * The workflow will only be executed if the predicate returns true.
   *
   * @param workflow The workflow to execute
   * @param predicate Condition that determines if the workflow should be executed
   * @param propertyMap Map of target property names to source property names for input mapping
   */
  fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    propertyMap: Map<String, String> = emptyMap()
  ) {
    otherMethodCalled = true
    currentBuilder.thenIf(workflow, predicate, propertyMap)
  }

  /**
   * Adds a workflow to be executed conditionally after the previous workflow
   * with a property mapping builder for input mapping
   *
   * The workflow will only be executed if the predicate returns true.
   *
   * @param workflow The workflow to execute
   * @param predicate Condition that determines if the workflow should be executed
   * @param block Property mapping configuration block
   */
  fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    block: PropertyMappingBuilder.() -> Unit
  ) {
    otherMethodCalled = true
    currentBuilder.thenIf(workflow, predicate, buildPropertyMap(block))
  }

  /**
   * Builds a UseCase from the configured workflow chain
   *
   * @return A UseCase that will execute the configured workflow chain
   * @throws IllegalStateException if first() method was not called
   */
  fun build(): UseCase<UCC> {
    if (!firstCalled) {
      throw IllegalStateException("first() method must be called to set the initial workflow")
    }

    val that = this
    return object : UseCase<UCC>() {
      override suspend fun execute(ucCommand: UCC): Either<WorkflowError, WorkflowResult> = either {
        that._command = ucCommand
        val workflow = that.initialWorkflow ?: raise(
          CompositionError(
            "Initial workflow not set",
            IllegalStateException("Initial workflow not set")
          )
        )

        // Create an empty initial result for auto-mapping
        val emptyResult = WorkflowResult()

        // Determine the initial workflow input
        val initialWorkflowInput = if (that.initialWorkflowMapper != null) {
          // Use explicit mapper if provided
          that.initialWorkflowMapper!!(ucCommand)
        } else {
          // Use auto-mapping
          @Suppress("UNCHECKED_CAST")
          val inputClass = WorkflowUtils.getWorkflowInputClass<WorkflowInput>(workflow)
            ?: raise(
              CompositionError(
                "Cannot determine input type for initial workflow",
                IllegalArgumentException("Cannot determine input type")
              )
            )

          // Use WorkflowUtils.autoMapInput
          WorkflowUtils.autoMapInput(emptyResult, ucCommand, that.initialPropertyMap, inputClass)
            ?: raise(
              CompositionError(
                "Cannot auto-map to ${inputClass.simpleName}",
                AutoMappingException("Cannot auto-map to ${inputClass.simpleName}")
              )
            )
        }

        // Execute the initial workflow
        val interceptors = that.interceptors
        val initialResult = workflow.execute(initialWorkflowInput, interceptors = interceptors)

        // Execute the rest of the workflow chain
        return initialResult
          .mapLeft { ExecutionError("No workflows found") }
          .flatMap { result ->
            either {
              builders.fold(result.right() as Either<WorkflowError, WorkflowResult>) { workflowResult, builder ->
                val builtWorkflow = builder.build()
                builtWorkflow.execute(initialWorkflowInput, workflowResult.bind(), ucCommand, interceptors)
              }.bind()
            }
          }
      }
    }
  }
}

internal abstract class BuiltWorkflow<UCC : UseCaseCommand, I : WorkflowInput, E : Event> {
  abstract suspend fun execute(
    input: I,
    result: WorkflowResult,
    command: UCC,
    interceptors: List<WorkflowInterceptor>
  ): Either<WorkflowError, WorkflowResult>
}

internal class WorkflowStep<UCC : UseCaseCommand, I : WorkflowInput, E : Event>(
  val step: suspend (
    WorkflowResult,
    WorkflowContext,
    UCC,
    List<WorkflowInterceptor>
  ) -> Either<WorkflowError, WorkflowResult>
)

/**
 * Utility functions for workflow operations
 */
object WorkflowUtils {
  /**
   * Determines the input type class for a workflow
   */
  @Suppress("UNCHECKED_CAST")
  fun <C : WorkflowInput> getWorkflowInputClass(workflow: Workflow<*, *>): KClass<C>? {
    return workflow.javaClass.kotlin.supertypes[0].arguments[0].type?.classifier as? KClass<C>
  }

  /**
   * Maps properties from various sources to create a workflow input
   */
  fun <T : WorkflowInput> autoMapInput(
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

    return constructor.runCatching { callBy(args) }.getOrNull()
  }

  /**
   * Finds a property value in a list of events
   */
  private fun findInEvents(events: List<Event>, propertyName: String?, targetType: KClass<*>?): Any? {
    if (propertyName == null) return null

    return events.firstNotNullOfOrNull { event ->
      val eventClass = event::class
      val property = eventClass.memberProperties.find { it.name == propertyName }
      property?.let {
        runCatching { it.getter.call(event) }
          .getOrNull()
          ?.takeIf { value -> targetType == null || targetType.isInstance(value) }
      }
    }
  }

  /**
   * Finds a property value in a command
   */
  private fun findInCommand(command: UseCaseCommand, propertyName: String?): Any? {
    if (propertyName == null) return null
    val commandClass = command::class
    val property = commandClass.memberProperties.find { it.name == propertyName }
    return property?.getter?.runCatching { call(command) }?.getOrNull()
  }
}

/**
 * Extension function to auto-map and execute a workflow
 */
suspend fun <C : WorkflowInput, R : Event, UCC : UseCaseCommand> WorkflowResult.autoMapInputAndExecuteNext(
  workflow: Workflow<C, R>,
  command: UCC,
  propertyMap: Map<String, String> = emptyMap(),
  clazz: KClass<C>,
  interceptors: List<WorkflowInterceptor> = emptyList()
): Either<WorkflowError, WorkflowResult> = Either.catch {
  WorkflowUtils.autoMapInput(this, command, propertyMap, clazz)
    ?: throw AutoMappingException("Cannot auto-map to ${clazz.simpleName}")
}
  .mapLeft { ex -> CompositionError("Error mapping input: ${ex.message ?: "Unknown error"}", ex) }
  .flatMap { input -> workflow.execute(input, this.context, interceptors) }

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

  /**
   * Creates a workflow step that auto-maps input and executes the workflow
   */
  protected fun <C : WorkflowInput, R : Event> createWorkflowStep(
    workflow: Workflow<C, R>,
    propertyMap: Map<String, String>,
    predicate: ((WorkflowResult) -> Boolean)? = null,
    withCoroutineScope: Boolean = false
  ): WorkflowStep<UCC, I, E> {
    return WorkflowStep { result, context, command, interceptors ->
      val executeStep: suspend () -> Either<WorkflowError, WorkflowResult> = {
        if (predicate == null || predicate(result)) {
          val clazz = WorkflowUtils.getWorkflowInputClass<C>(workflow)
            ?: throw IllegalArgumentException("Cannot determine input type for workflow")
          result.autoMapInputAndExecuteNext(
            workflow,
            command,
            propertyMap,
            clazz,
            interceptors
          )
        } else {
          // Return the original result when predicate is false
          result.right()
        }
      }

      if (withCoroutineScope) {
        coroutineScope { executeStep() }
      } else {
        executeStep()
      }
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
    workflows.add(createWorkflowStep(workflow, propertyMap))
  }

  override fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    propertyMap: Map<String, String>
  ) {
    workflows.add(createWorkflowStep(workflow, propertyMap, predicate))
  }

  override fun build(): BuiltWorkflow<UCC, I, E> {
    return object : BuiltWorkflow<UCC, I, E>() {
      override suspend fun execute(
        input: I,
        result: WorkflowResult,
        command: UCC,
        interceptors: List<WorkflowInterceptor>
      ): Either<WorkflowError, WorkflowResult> {
        return workflows.fold(result.right() as Either<WorkflowError, WorkflowResult>) { currentResult, workflow ->
          when (currentResult) {
            is Either.Right -> {
              val resultValue = currentResult.value
              val nextResult = workflow.step(resultValue, resultValue.context, command, interceptors)
              nextResult.fold(
                { currentResult }, // Keep error from previous step
                { workflowResult ->
                  // If the returned result is the same as the original result (reference equality),
                  // it means the workflow step didn't execute (predicate was false)
                  if (workflowResult === resultValue) {
                    workflowResult.right()
                  } else {
                    workflowResult.combine(resultValue).right()
                  }
                }
              )
            }
            is Either.Left -> currentResult // Short-circuit on error
          }
        }
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
    workflows.add(createWorkflowStep(workflow, propertyMap, withCoroutineScope = true))
  }

  override fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    propertyMap: Map<String, String>
  ) {
    workflows.add(createWorkflowStep(workflow, propertyMap, predicate, withCoroutineScope = true))
  }

  override fun build(): BuiltWorkflow<UCC, I, E> {
    return object : BuiltWorkflow<UCC, I, E>() {
      override suspend fun execute(
        input: I,
        result: WorkflowResult,
        command: UCC,
        interceptors: List<WorkflowInterceptor>
      ): Either<WorkflowError, WorkflowResult> {
        val deferredResults = coroutineScope {
          workflows.map { workflow ->
            async { workflow.step(result, result.context, command, interceptors) }
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

/**
 * Exception thrown when auto-mapping fails
 *
 * This exception is thrown when the system cannot automatically map properties
 * from source objects (events, commands, context) to a workflow input.
 *
 * @param message Description of the mapping failure
 */
class AutoMappingException(message: String) : Exception(message)

/**
 * Builder for creating property mappings between workflow inputs
 *
 * This class provides a DSL for configuring property mappings between
 * source properties (from events, commands, or context) and target properties
 * (workflow input parameters).
 */
class PropertyMappingBuilder {
  private val mappings = mutableMapOf<String, String>()

  /**
   * Maps a target property to a source property
   *
   * @receiver The target property name in the workflow input
   * @param sourceName The source property name from events, commands, or context
   */
  infix fun String.from(sourceName: String) {
    mappings[this] = sourceName
  }

  /**
   * Builds the property mapping
   *
   * @return A map of target property names to source property names
   */
  fun build(): Map<String, String> = mappings.toMap()
}

/**
 * Utility function to build a property mapping from a configuration block
 *
 * @param block The property mapping configuration block
 * @return A map of target property names to source property names
 */
private fun buildPropertyMap(block: PropertyMappingBuilder.() -> Unit): Map<String, String> {
  val builder = PropertyMappingBuilder()
  builder.block()
  return builder.build()
}

/**
 * Configuration block for parallel workflow execution
 *
 * This class provides a DSL for configuring workflows to be executed in parallel.
 * Workflows added to this block will be executed concurrently.
 */
class ParallelBlock<UCC : UseCaseCommand, I : WorkflowInput, E : Event> internal constructor() {
  private val builder = ParallelWorkflowChainBuilder<UCC, I, E>()

  /**
   * Adds a workflow to be executed in parallel
   *
   * @param workflow The workflow to execute
   * @param propertyMap Map of target property names to source property names for input mapping
   */
  fun <C : WorkflowInput, R : Event> then(
    workflow: Workflow<C, R>,
    propertyMap: Map<String, String> = emptyMap()
  ) {
    builder.then(workflow, propertyMap)
  }

  /**
   * Adds a workflow to be executed in parallel with a property mapping builder
   *
   * @param workflow The workflow to execute
   * @param block Property mapping configuration block
   */
  fun <C : WorkflowInput, R : Event> then(
    workflow: Workflow<C, R>,
    block: PropertyMappingBuilder.() -> Unit
  ) {
    builder.then(workflow, buildPropertyMap(block))
  }

  /**
   * Adds a workflow to be executed conditionally in parallel
   *
   * The workflow will only be executed if the predicate returns true.
   *
   * @param workflow The workflow to execute
   * @param predicate Condition that determines if the workflow should be executed
   * @param propertyMap Map of target property names to source property names for input mapping
   */
  fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    propertyMap: Map<String, String> = emptyMap()
  ) {
    builder.thenIf(workflow, predicate, propertyMap)
  }

  /**
   * Adds a workflow to be executed conditionally in parallel with a property mapping builder
   *
   * The workflow will only be executed if the predicate returns true.
   *
   * @param workflow The workflow to execute
   * @param predicate Condition that determines if the workflow should be executed
   * @param block Property mapping configuration block
   */
  fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    block: PropertyMappingBuilder.() -> Unit
  ) {
    builder.thenIf(workflow, predicate, buildPropertyMap(block))
  }

  /**
   * Builds the parallel workflow chain
   *
   * @return A built workflow that will execute all configured workflows in parallel
   */
  internal fun build() = builder.build()
}
