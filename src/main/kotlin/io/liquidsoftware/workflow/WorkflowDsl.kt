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
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.full.cast
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
  private var initialWorkflow: Workflow<*, *>? = null
  private var initialWorkflowInputClass: KClass<out WorkflowInput>? = null
  private var initialPropertyMapping: PropertyMapping = PropertyMapping.EMPTY
  private var firstCalled = false
  private var otherMethodCalled = false

  // Collection of workflow chain builders
  private val builders = mutableListOf<BaseWorkflowChainBuilder<UCC, WorkflowInput, Event>>()
  private var currentBuilder = SequentialWorkflowChainBuilder<UCC, WorkflowInput, Event>()

  init {
    builders.add(currentBuilder)
  }


  /**
   * Sets the initial workflow in the chain with a property mapping for input mapping
   *
   * This method must be called exactly once and must be the first method called
   * in the DSL configuration block.
   *
   * @param workflow The initial workflow to execute
   * @param propertyMapping PropertyMapping with type-safe mappings for input mapping
   * @throws IllegalStateException if called more than once or after other methods
   */
  fun <WFI : WorkflowInput, E : Event> first(
    workflow: Workflow<WFI, E>,
    propertyMapping: PropertyMapping = PropertyMapping.EMPTY
  ) {
    if (firstCalled) {
      throw IllegalStateException("first() method can only be called once")
    }
    if (otherMethodCalled) {
      throw IllegalStateException("first() method must be the first method called")
    }
    initialWorkflow = workflow
    initialWorkflowInputClass = WorkflowUtils.getWorkflowInputClass(workflow)
    initialPropertyMapping = propertyMapping
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
    first(workflow, buildPropertyMapping(block))
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
        propertyMapping: PropertyMapping
      ) {
        throw UnsupportedOperationException("Cannot add workflows to a parallel block after it's created")
      }

      override fun <C : WorkflowInput, R : Event> thenIf(
        workflow: Workflow<C, R>,
        predicate: (WorkflowResult) -> Boolean,
        propertyMapping: PropertyMapping
      ) {
        throw UnsupportedOperationException("Cannot add workflows to a parallel block after it's created")
      }

      override fun build() = parallelBlock.build()
    })
    currentBuilder = SequentialWorkflowChainBuilder()
    builders.add(currentBuilder)
  }

  /**
   * Adds a workflow to be executed sequentially after the previous workflow
   *
   * @param workflow The workflow to execute
   * @param propertyMapping PropertyMapping with type-safe mappings for input mapping
   */
  fun <C : WorkflowInput, R : Event> then(
    workflow: Workflow<C, R>,
    propertyMapping: PropertyMapping = PropertyMapping.EMPTY
  ) {
    otherMethodCalled = true
    currentBuilder.then(workflow, propertyMapping)
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
    currentBuilder.then(workflow, buildPropertyMapping(block))
  }

  /**
   * Adds a workflow to be executed conditionally after the previous workflow
   *
   * The workflow will only be executed if the predicate returns true.
   *
   * @param workflow The workflow to execute
   * @param predicate Condition that determines if the workflow should be executed
   * @param propertyMapping PropertyMapping with type-safe mappings for input mapping
   */
  fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    propertyMapping: PropertyMapping = PropertyMapping.EMPTY
  ) {
    otherMethodCalled = true
    currentBuilder.thenIf(workflow, predicate, propertyMapping)
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
    currentBuilder.thenIf(workflow, predicate, buildPropertyMapping(block))
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
      override suspend fun execute(command: UCC): Either<WorkflowError, WorkflowResult> = either {
        val workflow = that.initialWorkflow ?: raise(
          CompositionError(
            "Initial workflow not set",
            IllegalStateException("Initial workflow not set")
          )
        )
        val initialInputClass = that.initialWorkflowInputClass ?: WorkflowUtils.getWorkflowInputClass<WorkflowInput>(workflow)
          ?: raise(
            CompositionError(
              "Cannot determine input type for initial workflow",
              IllegalArgumentException("Cannot determine input type")
            )
          )

        // Create an empty initial result for auto-mapping
        val emptyResult = WorkflowResult()

        // Determine the initial workflow input
        val initialWorkflowInput = WorkflowUtils.autoMapInput(emptyResult, command, that.initialPropertyMapping, initialInputClass)
          ?: raise(
            CompositionError(
              "Cannot auto-map to ${initialInputClass.simpleName}",
              AutoMappingException("Cannot auto-map to ${initialInputClass.simpleName}")
            )
          )

        // Execute the initial workflow
        val initialResult = executeInitialWorkflow(workflow, initialInputClass.cast(initialWorkflowInput))

        // Execute the rest of the workflow chain
        return initialResult
          .mapLeft { error -> ExecutionError("Initial workflow failed: $error") }
          .flatMap { result ->
            either {
              builders.fold<BaseWorkflowChainBuilder<UCC, WorkflowInput, Event>, Either<WorkflowError, WorkflowResult>>(
                result.right()
              ) { workflowResult, builder ->
                val builtWorkflow = builder.build()
                builtWorkflow.execute(initialWorkflowInput, workflowResult.bind(), command)
              }.bind()
            }
          }
      }
    }
  }

  @Suppress("UNCHECKED_CAST")
  private suspend fun executeInitialWorkflow(
    workflow: Workflow<*, *>,
    input: WorkflowInput
  ): Either<WorkflowError, WorkflowResult> {
    return (workflow as Workflow<WorkflowInput, Event>).execute(input)
  }
}

internal abstract class BuiltWorkflow<UCC : UseCaseCommand, I : WorkflowInput, E : Event> {
  abstract suspend fun execute(input: I, result: WorkflowResult, command: UCC): Either<WorkflowError, WorkflowResult>
}

internal class WorkflowStep<UCC : UseCaseCommand>(
  val step: suspend (WorkflowResult, UCC) -> Either<WorkflowError, WorkflowResult>
)

/**
 * Utility functions for workflow operations
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
   * Determines the input type class for a workflow
   */
  @Suppress("UNCHECKED_CAST")
  fun <C : WorkflowInput> getWorkflowInputClass(workflow: Workflow<*, *>): KClass<C>? {
    val inputType = findWorkflowInputType(workflow::class, emptyMap()) ?: return null
    return inputType.classifier as? KClass<C>
  }
  /**
   * Maps properties from various sources to create a workflow input with type-safe validation
   */
  fun <T : WorkflowInput> autoMapInput(
    result: WorkflowResult,
    command: UseCaseCommand,
    propertyMapping: PropertyMapping = PropertyMapping.EMPTY,
    clazz: KClass<T>
  ): T? {
    val constructor = clazz.primaryConstructor ?: return null
    val args = mutableMapOf<KParameter, Any?>()

    for (param in constructor.parameters) {
        val paramName = param.name
        val sourceKey = paramName?.let { propertyMapping.typedMappings[it] }
        val value = when {
          // Check for typed mapping
          sourceKey != null -> {
              val expectedType = param.type.classifier as? KClass<*>
              val sourceType = sourceKey.type

              // Validate type compatibility before looking for value
              if (expectedType != null && expectedType != sourceType) {
                  // Type mismatch - this will be handled at composition time
                  return null
              }

              findTypedValue(result, command, sourceKey)
          }

          // Default to parameter name with type checking
          else -> {
              val expectedType = param.type.classifier as? KClass<*>
              paramName?.let { name -> findValue(result, command, name, expectedType) }
          }
        }

        when {
            value != null -> args[param] = value
            param.isOptional -> Unit // Allow default value to be used
            param.type.isMarkedNullable -> args[param] = null
            else -> return null // Cannot satisfy required parameter
        }
    }

    return constructor.runCatching { callBy(args) }.getOrNull()
  }

  /**
   * Type-safe value finder using Key<T>
   */
  private fun <T : Any> findTypedValue(
    result: WorkflowResult,
    command: UseCaseCommand,
    sourceKey: Key<T>
  ): T? {
    // Try events first
    findInEventsTyped(result.events, sourceKey)?.let { return it }

    // Try command
    findInCommandTyped(command, sourceKey)?.let { return it }

    // Try context
    result.context.data[sourceKey.id]?.let { value ->
        if (sourceKey.type.isInstance(value)) {
            @Suppress("UNCHECKED_CAST")
            return value as T
        }
    }

    return null
  }

  /**
   * Type-safe event property finder
   */
  private fun <T : Any> findInEventsTyped(events: List<Event>, sourceKey: Key<T>): T? {
    return events.firstNotNullOfOrNull { event ->
        val eventClass = event::class
        val property = eventClass.memberProperties.find { it.name == sourceKey.id }
        property?.let { prop ->
          runCatching { prop.getter.call(event) }
                .getOrNull()
                ?.takeIf { value -> sourceKey.type.isInstance(value) }
                ?.let {
                    @Suppress("UNCHECKED_CAST")
                    it as T
                }
        }
    }
  }

  /**
   * Type-safe command property finder
   */
  private fun <T : Any> findInCommandTyped(command: UseCaseCommand, sourceKey: Key<T>): T? {
    val commandClass = command::class
    val property = commandClass.memberProperties.find { it.name == sourceKey.id }
    return property?.getter?.runCatching { call(command) }
        ?.getOrNull()
        ?.takeIf { sourceKey.type.isInstance(it) }
        ?.let {
            @Suppress("UNCHECKED_CAST")
            it as T
        }
  }

  /**
   * Finds a property value with type checking (fallback for default mappings)
   */
  private fun findValue(result: WorkflowResult, command: UseCaseCommand, propertyName: String, targetType: KClass<*>?): Any? {
    // Try to find matching event property
    findInEvents(result.events, propertyName, targetType)?.let { return it }

    // Try to find in command
    findInCommand(command, propertyName)?.let { return it }

    // Try context data
    return result.context.data[propertyName]?.takeIf { value ->
        targetType == null || targetType.isInstance(value)
    }
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
 * Extension function to auto-map and execute a workflow with type-safe validation
 */
suspend fun <C : WorkflowInput, R : Event, UCC : UseCaseCommand> WorkflowResult.autoMapInputAndExecuteNext(
  workflow: Workflow<C, R>,
  command: UCC,
  propertyMapping: PropertyMapping = PropertyMapping.EMPTY,
  clazz: KClass<C>
): Either<WorkflowError, WorkflowResult> = Either.catch {
  WorkflowUtils.autoMapInput(this, command, propertyMapping, clazz)
    ?: throw AutoMappingException("Cannot auto-map to ${clazz.simpleName}")
}
  .mapLeft { ex -> CompositionError("Error mapping input: ${ex.message ?: "Unknown error"}", ex) }
  .flatMap { input -> workflow.execute(input) }

internal abstract class BaseWorkflowChainBuilder<UCC : UseCaseCommand, I : WorkflowInput, E : Event> {
  protected val workflows = mutableListOf<WorkflowStep<UCC>>()

  abstract fun <C : WorkflowInput, R : Event> then(
    workflow: Workflow<C, R>,
    propertyMapping: PropertyMapping = PropertyMapping.EMPTY
  )

  abstract fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    propertyMapping: PropertyMapping = PropertyMapping.EMPTY
  )

  /**
   * Creates a workflow step that auto-maps input and executes the workflow
   */
  protected fun <C : WorkflowInput, R : Event> createWorkflowStep(
    workflow: Workflow<C, R>,
    propertyMapping: PropertyMapping,
    predicate: ((WorkflowResult) -> Boolean)? = null,
    withCoroutineScope: Boolean = false
  ): WorkflowStep<UCC> {
    return WorkflowStep { result, command ->
      val executeStep: suspend () -> Either<WorkflowError, WorkflowResult> = {
        if (predicate == null || predicate(result)) {
          val clazz = WorkflowUtils.getWorkflowInputClass<C>(workflow)
            ?: throw IllegalArgumentException("Cannot determine input type for workflow")
          result.autoMapInputAndExecuteNext(workflow, command, propertyMapping, clazz)
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
    propertyMapping: PropertyMapping
  ) {
    workflows.add(createWorkflowStep(workflow, propertyMapping))
  }

  override fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    propertyMapping: PropertyMapping
  ) {
    workflows.add(createWorkflowStep(workflow, propertyMapping, predicate))
  }

  override fun build(): BuiltWorkflow<UCC, I, E> {
    return object : BuiltWorkflow<UCC, I, E>() {
      override suspend fun execute(input: I, result: WorkflowResult, command: UCC): Either<WorkflowError, WorkflowResult> {
        return workflows.fold<WorkflowStep<UCC>, Either<WorkflowError, WorkflowResult>>(
          result.right()
        ) { currentResult, workflow ->
          when (currentResult) {
            is Either.Right -> {
              val resultValue = currentResult.value
              val nextResult = workflow.step(resultValue, command)
              nextResult.fold(
                { error -> Either.Left(error) }, // Propagate error from failed workflow step
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
    propertyMapping: PropertyMapping
  ) {
    workflows.add(createWorkflowStep(workflow, propertyMapping, withCoroutineScope = true))
  }

  override fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    propertyMapping: PropertyMapping
  ) {
    workflows.add(createWorkflowStep(workflow, propertyMapping, predicate, withCoroutineScope = true))
  }

  override fun build(): BuiltWorkflow<UCC, I, E> {
    return object : BuiltWorkflow<UCC, I, E>() {
      override suspend fun execute(input: I, result: WorkflowResult, command: UCC): Either<WorkflowError, WorkflowResult> {
        val deferredResults = coroutineScope {
          workflows.map { workflow ->
            async { workflow.step(result, command) }
          }
        }
        val results = deferredResults.awaitAll()
        val filteredResults = results.filter { newResult ->
          newResult is Either.Left || (newResult is Either.Right && newResult.value !== result)
        }

        val newExecutions = filteredResults.fold(result.context.executions) { acc, newResult ->
          newResult.fold(
            { acc },
            { acc + it.context.executions }
          )
        }

        val combinedResult = filteredResults.fold<Either<WorkflowError, WorkflowResult>, Either<WorkflowError, WorkflowResult>>(
          result.right()
        ) { acc, newResult ->
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
 * Container for type-safe property mappings
 */
data class PropertyMapping(
    val typedMappings: Map<String, Key<*>> = emptyMap()
) {
    companion object {
        val EMPTY = PropertyMapping()
    }
}

/**
 * Builder for creating type-safe property mappings between workflow inputs
 *
 * This class provides a DSL for configuring type-safe property mappings between
 * source properties (from events, commands, or context) and target properties
 * (workflow input parameters).
 */
class PropertyMappingBuilder {
    private val typedMappings = mutableMapOf<String, Key<*>>()

    /**
     * Maps a target property to a type-safe source key
     */
    infix fun <T : Any> String.from(sourceKey: Key<T>) {
        typedMappings[this] = sourceKey
    }

    /**
     * Builds the property mapping
     */
    fun build(): PropertyMapping = PropertyMapping(typedMappings = typedMappings.toMap())
}

/**
 * Utility function to build a property mapping from a configuration block
 *
 * @param block The property mapping configuration block
 * @return A PropertyMapping with type-safe mappings
 */
private fun buildPropertyMapping(block: PropertyMappingBuilder.() -> Unit): PropertyMapping {
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
   * @param propertyMapping PropertyMapping with type-safe mappings for input mapping
   */
  fun <C : WorkflowInput, R : Event> then(
    workflow: Workflow<C, R>,
    propertyMapping: PropertyMapping = PropertyMapping.EMPTY
  ) {
    builder.then(workflow, propertyMapping)
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
    builder.then(workflow, buildPropertyMapping(block))
  }

  /**
   * Adds a workflow to be executed conditionally in parallel
   *
   * The workflow will only be executed if the predicate returns true.
   *
   * @param workflow The workflow to execute
   * @param predicate Condition that determines if the workflow should be executed
   * @param propertyMapping PropertyMapping with type-safe mappings for input mapping
   */
  fun <C : WorkflowInput, R : Event> thenIf(
    workflow: Workflow<C, R>,
    predicate: (WorkflowResult) -> Boolean,
    propertyMapping: PropertyMapping = PropertyMapping.EMPTY
  ) {
    builder.thenIf(workflow, predicate, propertyMapping)
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
    builder.thenIf(workflow, predicate, buildPropertyMapping(block))
  }

  /**
   * Builds the parallel workflow chain
   *
   * @return A built workflow that will execute all configured workflows in parallel
   */
  internal fun build() = builder.build()
}
