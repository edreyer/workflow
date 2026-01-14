# UseCase Workflow Utility

## Table of Contents

<!-- TOC -->
* [UseCase Workflow Utility](#usecase-workflow-utility)
  * [Table of Contents](#table-of-contents)
  * [Overview](#overview)
  * [Why It Exists](#why-it-exists)
  * [Key Features](#key-features)
    * [Fluent DSL for Workflow Composition](#fluent-dsl-for-workflow-composition)
    * [Flexible Execution Models](#flexible-execution-models)
    * [Intelligent Data Mapping](#intelligent-data-mapping)
    * [Comprehensive Execution Context](#comprehensive-execution-context)
    * [Robust Error Handling](#robust-error-handling)
    * [Loose Coupling](#loose-coupling)
  * [Example: Basic Usage](#example-basic-usage)
  * [Core Concepts](#core-concepts)
    * [Workflows](#workflows)
      * [Workflows vs. Services](#workflows-vs-services)
      * [Benefits of the Workflow Approach](#benefits-of-the-workflow-approach)
    * [Use Cases](#use-cases)
      * [Key Characteristics](#key-characteristics)
      * [Flow Control Capabilities](#flow-control-capabilities)
      * [Organizational Benefits](#organizational-benefits)
      * [Implementation Patterns](#implementation-patterns)
    * [Inputs and Events](#inputs-and-events)
      * [Workflow Inputs](#workflow-inputs)
        * [Commands](#commands)
        * [Queries](#queries)
      * [Events](#events)
      * [Flow of Data](#flow-of-data)
      * [Benefits of This Approach](#benefits-of-this-approach)
    * [The Workflow DSL](#the-workflow-dsl)
    * [Core DSL Methods](#core-dsl-methods)
      * [`useCase { ... }`](#usecase---)
      * [`startWith { ... }`](#startwith---)
      * [`then(workflow)`](#thenworkflow)
      * [`thenIf(workflow, predicate)`](#thenifworkflow-predicate)
      * [`parallel { ... }`](#parallel---)
      * [`parallelJoin(...)`](#paralleljoin)
      * [`thenLaunch(workflow, timeout?)`](#thenlaunchworkflow-timeout-and-awaitlaunched)
      * [`awaitLaunched()`](#thenlaunchworkflow-timeout-and-awaitlaunched)
    * [Data Extraction Methods](#data-extraction-methods)
        * [`WorkflowResult.getFromEvent<T>(property)`](#workflowresultgetfromeventtproperty)
        * [`WorkflowContext.getTypedData<T>(key, default)`](#workflowcontextgettypeddatatkey-default)
      * [Reasoning About the DSL](#reasoning-about-the-dsl)
    * [Error Handling Strategies](#error-handling-strategies)
      * [Error Types and Their Purposes](#error-types-and-their-purposes)
        * [ValidationError](#validationerror)
        * [ExecutionError](#executionerror)
        * [ExceptionError](#exceptionerror)
        * [CompositionError](#compositionerror)
        * [ExecutionContextError](#executioncontexterror)
        * [ChainError](#chainerror)
        * [Error Handling Examples](#error-handling-examples)
        * [Failure Behavior](#failure-behavior)
        * [Logging Execution Timing](#logging-execution-timing)
        * [Logging Chain Failures](#logging-chain-failures)
      * [Global Error Handling Strategies](#global-error-handling-strategies)
        * [Error Propagation](#error-propagation)
        * [Error Transformation](#error-transformation)
        * [Error Recovery](#error-recovery)
      * [Best Practices](#best-practices)
      * [Code Examples](#code-examples)
        * [ValidationError Example](#validationerror-example)
        * [ExecutionError Example](#executionerror-example)
        * [ExceptionError Example](#exceptionerror-example)
        * [CompositionError Example](#compositionerror-example)
  * [Best Practices](#best-practices-1)
    * [Designing Effective Workflows](#designing-effective-workflows)
      * [Single Responsibility Principle](#single-responsibility-principle)
      * [Isolation and Independence](#isolation-and-independence)
      * [Statelessness and Determinism](#statelessness-and-determinism)
      * [Granular Error Handling](#granular-error-handling)
      * [Performance Considerations](#performance-considerations)
      * [Testing-Friendly Design](#testing-friendly-design)
    * [Structuring Complex Use Cases](#structuring-complex-use-cases)
      * [Layered Workflow Composition](#layered-workflow-composition)
      * [Effective Parallel Processing](#effective-parallel-processing)
      * [Conditional Execution Patterns](#conditional-execution-patterns)
        * [Decision Branching](#decision-branching)
        * [State-Based Processing](#state-based-processing)
      * [Complex Data Mapping Strategies](#complex-data-mapping-strategies)
      * [Long-Running Process Patterns](#long-running-process-patterns)
      * [Error Recovery Strategies](#error-recovery-strategies)
      * [Domain Event Sourcing Integration](#domain-event-sourcing-integration)
<!-- TOC -->


## Overview

The UseCase Workflow Utility is a Kotlin library that provides a structured approach to implementing complex business
logic in modern applications. It addresses the problem of scattered use case business logic,
organizing them into composable, independent workflows that can be orchestrated into cohesive use cases.

Inspired by functional programming principles, particularly function composition, this utility offers a type-safe DSL
(Domain Specific Language) that allows developers to explicitly define business processes as a series of workflow
steps. These steps can be arranged sequentially or in parallel, with conditional execution paths, while maintaining
loose coupling between individual components.

By structuring business logic this way, the UseCase utility brings clarity to complex operations that would
otherwise be distributed across multiple domain services, making codebases more maintainable, testable, and
easier to reason about.

## Why It Exists

See the figure below. This is an example of the business services used by a typical Java/Kotlin application. Notice the
dependency lines. Over time, as applications become more complex, the number of services grows, and the lines
connecting them tends to grow into a rats nest despite the best efforts of teams to avoid this.
This problem is simply inherent in this structure.

Additionally, the logic for each business use case is distributed across these services. When asked what the app does
for any use case, we must go spelunking into the codebase and trace call hierarchies to see all the things that
happen to satisfy each use case. **The use cases are effectively implicit**.

Business logic tends to get more complex over time. This complexity often forces us to introduce new tightly
coupled services, further distributing the business logic. Or the methods in these services begin to get more
complex with various branching conditions. It's easier to add just one more `if()` statement than to restructure
a large amount of code to satisfy one more new requirement. Death by a thousand cuts.

This `UseCase` pattern aims to bring all the logic together making our use cases explicit by binding the steps to
satisfy that use case into a loosely bound set of workflow steps. We've created a simple DSL that can be used to
accomplish this.

<figure>
  <img src="assets/high-coupling.png" alt="High Coupling Diagram">
  <figcaption>Figure 1: High Coupling among services</figcaption>
</figure>

## Key Features

### Fluent DSL for Workflow Composition
- **Intuitive Chain Building**: Compose business processes by passing a typed `WorkflowState` through `startWith`, `then`, and companion helpers
- **Declarative Syntax**: Chain `startWith`, `then`, `thenIf`, `parallel`, and `parallelJoin` to express the flow of state and events with clear intent
- **Minimal Boilerplate**: Focus on business logic while the DSL handles sequencing, error propagation, and `WorkflowResult` merging

### Flexible Execution Models
- **Sequential Processing**: Execute workflows in order, with each step building on previous results
- **Parallel Execution**: Run independent workflows concurrently to optimize performance
- **Conditional Execution**: Use `thenIf` to dynamically control workflow execution based on previous results

### Intelligent State Handling
- **State-Based Transitions**: Pass typed `WorkflowState` between steps to maintain type safety throughout the chain
- **Deterministic Merging**: Automatically merge events and context while advancing the primary domain state
- **Type Compatibility**: Runtime validation ensures the output state of one workflow matches the input of the next (no auto-mapping)

### Comprehensive Execution Context
- **Metadata Collection**: Automatically track execution timing, workflow IDs, and success status
- **Context Sharing**: Share non-domain data between workflows through the WorkflowContext
- **Execution History**: Maintain a complete audit trail of all executed workflows within a use case (including failure metadata via ExecutionContextError)

### Robust Error Handling
- **Exception-Free Execution**: Workflow execution returns typed Either values; DSL misuse throws configuration errors
- **Granular Error Types**: Distinguish between validation errors, execution errors, and unexpected exceptions
- **Short-Circuit Execution**: Automatically stop the workflow chain when an error occurs
- **Predictable Control Flow**: Make error paths explicit with algebraic data types rather than exceptions

### Loose Coupling
- **Isolated Workflows**: Each workflow is independent with clearly defined inputs and outputs
- **No Direct Dependencies**: Workflows never directly invoke other workflows
- **Composition Over Inheritance**: Build complex behavior by composing simple workflows rather than inheritance hierarchies

## Example: Basic Usage

Here's how it looks to construct and execute a use case:

```kotlin
val orderProcessingUseCase: UseCase<ProcessOrderCommand> = useCase {
  startWith { command ->
    OrderDraftState(
      orderId = command.orderId,
      customerId = command.customerId,
      items = command.items,
      totalAmount = command.totalAmount
    ).right()
  }

  then(ValidateOrderWorkflow("validate-order"))

  parallelJoin(
    CalculateSubtotalWorkflow("calc-subtotal"),
    CalculateTaxWorkflow("calc-tax"),
  ) { subtotal, tax ->
    PricingState(
      orderId = subtotal.orderId,
      customerId = subtotal.customerId,
      items = subtotal.items,
      totalAmount = subtotal.totalAmount,
      shippingAddress = subtotal.shippingAddress,
      subtotal = subtotal.subtotal,
      tax = tax.tax,
      total = subtotal.subtotal + tax.tax
    )
  }

  parallel {
    then(CheckInventoryWorkflow("check-inventory"))
    then(ProcessPaymentWorkflow("process-payment"))
  }

  thenIf(
    PrepareShipmentWorkflow("prepare-shipment")
  ) {
    result ->
      val paymentSuccessful = when (val payment = result.getFromEvent(PaymentProcessedEvent::payment)) {
        is SuccessfulPayment -> true
        else -> false
      }
      result.context.getTypedData<Boolean>("inventoryAvailable") == true && paymentSuccessful
  }
}

runBlocking {
  val result = orderProcessingUseCase.execute(initialCommand)
  when (result) {
    is Either.Right -> println("UseCase emitted ${result.value.events.size} events")
    is Either.Left -> println("Use case failed: ${result.value}")
  }
}
```

## Core Concepts

### Workflows

A Workflow represents a discrete, focused step in a business process that performs a specific task with clear inputs and outputs. In traditional application architectures, business logic is often scattered across service classes that grow increasingly complex and coupled over time. Workflows offer a more structured alternative.

Each Workflow encapsulates a single responsibility or operation within your domain. For example, when registering a user, instead of having a monolithic `UserService` with a large `registerUser()` method, you might break this down into several focused Workflows:

1. `ValidateUserDataWorkflow` - Validates email, password requirements, etc.
2. `CheckUserExistsWorkflow` - Ensures the user doesn't already exist
3. `CreateUserAccountWorkflow` - Persists the user to the database
4. `GenerateAuthTokenWorkflow` - Creates authentication tokens
5. `SendWelcomeEmailWorkflow` - Dispatches welcome communications

These individual Workflows can then be assembled into a complete `UserRegistrationUseCase` using the provided DSL.

#### Workflows vs. Services

While Services and Workflows can coexist in your architecture, they serve different purposes:

**Services** provide technical capabilities and infrastructure access. They answer "how" questions:
- How to send an email
- How to store data in a database
- How to generate a token

**Workflows** implement business logic and rules. They answer "what" questions:
- What happens when a user registers
- What validation rules apply to user data
- What events should be triggered after user creation

In this model, Services become simpler, more focused tools that Workflows can leverage. A `SendWelcomeEmailWorkflow` might use an `EmailService`, but the workflow itself contains the business logic about when, why, and what email should be sent.

#### Benefits of the Workflow Approach

- **Modularity**: Each workflow has a single responsibility, making it easier to understand and test
- **Reusability**: Workflows can be reused across different use cases
- **Composability**: Complex processes can be built by combining simple workflows
- **Testability**: Isolated workflows with clear inputs and outputs are easier to test
- **Maintainability**: When business rules change, you can modify or replace specific workflows without affecting the entire process

By modeling your business processes as compositions of discrete Workflows rather than complex service methods, you create a more maintainable, testable, and flexible codebase that better reflects your domain.

### Use Cases

A UseCase is a composed set of Workflow instances that represents a complete business process or feature in your application. It serves as the entry point for business logic execution and provides an explicit, declarative definition of what your application does.

#### Key Characteristics

- **Explicit Process Definition**: UseCases make your business processes visible and explicit, rather than implicit and scattered across services
- **Composition-Based**: Built by composing multiple Workflows into a coherent sequence using a fluent DSL
- **Flow Control**: Provides sophisticated control over the execution flow through methods like `startWith`, `then`, `thenIf`, and `parallel`
- **Single Responsibility**: Each UseCase represents one complete business capability, following the Single Responsibility Principle
- **Error Handling**: Manages errors consistently across the entire process using type-safe error handling

#### Flow Control Capabilities

- **Sequential Execution**: Chain workflows one after another with `startWith` and `then` methods
- **Conditional Execution**: Use `thenIf` to conditionally execute workflows based on the results of previous steps
- **Parallel Processing**: Execute multiple workflows concurrently using the `parallel` block to optimize performance
- **Join & Merge**: Use `parallelJoin` to fan-out/fan-in when you need merged outputs
- **Fire-and-Forget**: Use `thenLaunch`/`awaitLaunched` for non-blocking side effects when you don’t want failures to stop the chain

#### Organizational Benefits

- **Centralized Business Logic**: Co-locate all your use cases in one place, making it easy to see what your application does
- **Self-Documenting**: The DSL makes the steps of each process clear and readable, serving as living documentation
- **Process Visibility**: Makes it easy to see not only what use cases your application supports, but also the exact steps each use case performs
- **Evolutionary Design**: Easily add, remove, or reorder workflow steps as your business requirements evolve

#### Implementation Patterns

UseCases can be created in two ways:

1. **Using the DSL**: The recommended approach that leverages the fluent builder pattern
   ```kotlin
   val registerUserUseCase = useCase<RegisterUserCommand> {
     startWith { command -> InitialState(command).right() }
     then(validateUserWorkflow)
     then(createUserWorkflow)
     then(sendWelcomeEmailWorkflow)
   }
   ```

2. **Through Direct Implementation**: For situations requiring custom behavior beyond what the DSL provides
   ```kotlin
   class RegisterUserUseCase : UseCase<RegisterUserCommand>() {
     override suspend fun execute(command: RegisterUserCommand): Either<WorkflowError, UseCaseEvents> {
       // Custom implementation
     }
   }
   ```

By making your use cases explicit and co-locating them, you gain a clear picture of your application's capabilities and can more easily maintain, test, and evolve your business processes.

### Inputs and Events

The Workflow pattern uses a carefully designed set of data structures for communication between workflows and for modeling the inputs and outputs of business processes. These structures are inspired by the Command Query Responsibility Segregation (CQRS) pattern, which separates read operations from write operations.

#### Workflow Inputs

Inputs represent the data and instructions passed to workflows. There are two distinct types of inputs, each with a specific purpose:

##### Commands

Commands are instructions to perform an action that changes the state of the system:

- **Purpose**: Represent intentions to modify state or perform operations with side effects
- **Naming Convention**: Named with verbs in imperative form (e.g., `CreateUserCommand`, `ProcessPaymentCommand`)
- **Characteristics**: Contain all the data needed to perform the operation
- **Usage**: Used in workflows that create, update, or delete data, or trigger processes with side effects

##### Queries

Queries are requests for information that don't change the system state:

- **Purpose**: Retrieve data without modifying anything
- **Naming Convention**: Named with nouns or questions (e.g., `UserDetailsQuery`, `OrderStatusQuery`)
- **Characteristics**: Define the parameters needed to fetch specific information
- **Usage**: Used in read-only workflows that retrieve and potentially transform data

#### Events

Events represent the outcomes of workflow execution:

- **Purpose**: Capture what has happened as a result of a workflow's execution
- **Naming Convention**: Named in past tense (e.g., `UserCreatedEvent`, `PaymentProcessedEvent`)
- **Characteristics**:
    - Immutable records of something that has occurred
    - Contain a unique ID and timestamp
    - Include relevant domain data related to what happened
- **Usage**:
    - Serve as the output of workflows
    - Provide data for subsequent workflows in a chain
    - Can be collected by the UseCase for auditing or further processing

#### Flow of Data

The Workflow pattern establishes a clear flow of typed state and events:

1. **Input Reception**: A UseCase receives a Command or Query and immediately uses `startWith` to build the initial `WorkflowState`.
2. **Stateful Workflows**: Each workflow executes with the current `WorkflowState`, emits events, and returns an updated state bundled inside `WorkflowResult`.
3. **Result Merging**: `WorkflowResult.mergePrevious` keeps previous events/context while carrying the latest state forward, maintaining deterministic ordering.
4. **Continuation**: The next workflow in the chain receives the new state and repeats the process.
5. **Final Events**: When the chain completes, the UseCase wraps the accumulated events in `UseCaseEvents` so callers see what happened.

This state-first model keeps the data flow explicit and reduces the need for fragile auto-mapping between workflows.

#### Benefits of This Approach

- **Clear Intent**: The type of input (Command vs. Query) communicates the intent of the operation
- **Separation of Concerns**: Read operations are explicitly separated from write operations
- **Audit Trail**: Events provide a complete record of what has happened during processing
- **Data Flow Visibility**: The transformation of data between workflows is explicit and traceable
- **Immutable History**: Events represent an immutable history of what has occurred, supporting audit and debugging needs

### The Workflow DSL

The Workflow DSL (Domain Specific Language) provides a fluent, declarative way to compose workflows into use cases. It handles the complexity of workflow orchestration, data mapping, and error management, allowing you to focus on defining your business processes.

#### Core DSL Methods

##### `useCase { ... }`

- **Purpose**: Entry point for creating a use case using the builder pattern
- **Usage**: Wraps all other DSL methods in a configuration block
- **How it works**: Initializes a use case builder and returns a fully configured `UseCase<C>` instance
- **Example**: `val myUseCase = useCase<MyCommand> { ... }`

##### `startWith { ... }`

- **Purpose**: Constructs the initial `WorkflowState` for the chain
- **Usage**: Must be the first method invoked and converts the incoming command/query into a typed state
- **How it works**: Returns an `Either<WorkflowError, S>` where `S : WorkflowState`, setting the foundation for the rest of the pipeline
- **Example**:
  ```
  startWith { command ->
    Either.Right(InitialOrderState(
      orderId = command.orderId,
      items = command.items
    ))
  }
  ```

##### `then(workflow)`

- **Purpose**: Runs the next workflow in the chain and advances state
- **Usage**: Combine workflows sequentially with multiple `then` calls
- **How it works**: Casts the latest `WorkflowState` to the workflow’s input, executes it, merges its `WorkflowResult` (state, events, context), and passes the new state forward
- **Example**: `then(createUserWorkflow)`

##### `thenIf(workflow, predicate)`

- **Purpose**: Conditionally executes a workflow based on previous results
- **Usage**: Guard executions that depend on context or event data
- **How it works**: The predicate receives the current `WorkflowResult<out WorkflowState>`; the workflow runs only if the predicate returns true
- **Example**: `thenIf(sendWelcomeEmailWorkflow) { result -> result.context.getTypedData<Boolean>("emailVerified") == true }`

##### `parallel { ... }`

- **Purpose**: Executes side-effect workflows concurrently while keeping the primary state unchanged
- **Usage**: Run independent workflows that observe the current state but don’t produce a new one
- **How it works**: Executes each branch in its own coroutine, aggregates events/context, and returns the **original state** along with the combined metadata. Any state changes within parallel branches are discarded; it is intended for side-effects like logging or independent validation.
- **Example**:
  ```
  parallel {
    then(checkInventoryWorkflow)
    then(processPaymentWorkflow)
  }
  ```

##### `parallelJoin(...)`

- **Purpose**: Runs 2..8 workflows concurrently and merges their output states into one typed result
- **Usage**: Use when multiple branches need the same input state and you want to build a composite state plus events
- **How it works**: Runs the branches in parallel, collects each branch’s `WorkflowState`, merges them with the provided lambda, and preserves all branch events before appending the merged one
- **Example**:
  ```
  parallelJoin(loadCatalogs, fetchSerp) { catalogs, serp ->
    ReadyForConversion(
      searchId = catalogs.searchId,
      criteria = catalogs.criteria,
      catalogs = catalogs.catalogs,
      serpItineraries = serp.serpItineraries,
    )
  }
  ```

##### `thenLaunch(workflow, timeout?)` and `awaitLaunched()`

- **Purpose**: Fire-and-forget side-effect workflows (do not change state) that can later be awaited as a batch
- **Usage**: Use `thenLaunch(workflow)` to start side effects without blocking; call `awaitLaunched()` to wait for all launched work, merge their events/context, and capture failures without failing the chain. Optional per-launch `timeout` bounds how long a launched workflow can run.
- **How it works**:
  - `thenLaunch` starts the workflow in the use case scope and returns immediately; any failures are only surfaced when you `awaitLaunched()`.
  - `awaitLaunched()` waits for all pending launched workflows (no-op if none or already finished), merges their events/context into the current result, and records failures as `LaunchedFailureEvent` plus `context.data["launchedFailures"]` without short-circuiting the use case.
  - State is unchanged because launched workflows are limited to side effects (`Workflow<I, I>`).
  - Use `parallel { ... }` / `then(...)` instead if you need failures to stop the chain.

Simple launch/await example:
```kotlin
useCase<MyCommand> {
  startWith { cmd -> MyState(cmd.id).right() }
  thenLaunch(LogTelemetryWorkflow("telemetry"))
  thenLaunch(AuditWorkflow("audit"))
  awaitLaunched() // wait for telemetry + audit; merge events/context; do not fail on their errors
  then(NextWorkflow("next")) // continues after side effects are drained
}
```

Cancelling launched workflows after completion:
Launched work runs in the use case scope. If you don’t call `awaitLaunched()` before returning, you can cancel the whole scope from your caller to stop any lingering work (e.g., wrap `useCase.execute` in `withTimeout` or cancel the job). If you do await, there’s nothing left to cancel.

#### Data Extraction Methods

These methods can be used within predicates and transformations to extract data from workflow results:

##### `WorkflowResult.getFromEvent<T>(property)`

- **Purpose**: Extract a specific property from an event of a given type
- **Usage**: Used when you need to access a property from a specific event type
- **How it works**: Searches for the first event of type T and returns the specified property value
- **Example**: `result.getFromEvent(UserCreatedEvent::userId) ?: throw IllegalStateException("User ID not found")`

##### `WorkflowContext.getTypedData<T>(key, default)`

- **Purpose**: Retrieve a value from the workflow context with type safety
- **Usage**: Used to access data stored in the context between workflows
- **How it works**: Retrieves the value for the given key, cast to type T, with an optional default value
- **Example**: `result.context.getTypedData<Boolean>("validationPassed", false)`

#### Reasoning About the DSL

- **Declarative Flow**: The DSL lets you think about workflow composition declaratively rather than imperatively
- **Data Flow**: Data flows through the chain of workflows, with each workflow's output becoming input for the next
- **Context vs. Events**: Use events for domain data, and context for cross-cutting concerns or metadata
- **Error Handling**: All errors are propagated through the chain, with automatic short-circuiting on failure
- **Composability**: Small, focused workflows can be combined in different ways for different use cases

The DSL abstracts away the complexity of workflow execution while giving you precise control over the business process. This makes your code more readable, maintainable, and aligned with the language of your domain.

### Error Handling Strategies

Error handling in workflow-based applications requires careful consideration. The Workflow framework provides a structured approach to error management through the `WorkflowError` sealed class hierarchy, which categorizes errors into distinct types to enable precise handling strategies.

#### Error Types and Their Purposes

##### ValidationError

- **Purpose**: Represents errors in input validation
- **When to use**: When workflow inputs fail to meet business or format requirements
- **Characteristics**:
    - Contains a descriptive message explaining the validation failure
    - Does not wrap an exception since validation failures are expected conditions
    - Typically occurs early in a workflow chain
- **Handling strategy**:
    - Present validation issues to the user for correction
    - Map to appropriate user-friendly error messages
    - Log at INFO level (these are not system failures)

##### ExecutionError

- **Purpose**: Represents business rule violations or process failures
- **When to use**: When a workflow fails due to business constraints or process conditions
- **Characteristics**:
    - Contains a message describing the business rule violation
    - Represents failures that are part of the expected domain behavior
    - Often occurs during the main processing phase of a workflow
- **Handling strategy**:
    - Communicate the specific business constraint violation to the caller
    - Consider alternative flows or compensation actions
    - Log at WARNING level for analysis of business process friction points

##### ExceptionError

- **Purpose**: Wraps unexpected exceptions from external systems or runtime errors
- **When to use**: When integrating with external services, databases, or when handling unexpected runtime exceptions
- **Characteristics**:
    - Contains both a message and the original exception
    - Preserves the stack trace for debugging
    - Represents unexpected technical failures
- **Handling strategy**:
    - Implement retry mechanisms for transient failures
    - Use circuit breakers for external system integrations
    - Log at ERROR level with full exception details
    - Consider fallback mechanisms for critical operations

##### CompositionError

- **Purpose**: Represents errors in the composition or orchestration of workflows, including type validation failures
- **When to use**: When there are issues in the workflow chain setup, during inter-workflow communication, or when property mapping types don't match
- **Characteristics**:
    - Contains a message and the underlying exception
    - Occurs during the construction or execution of the workflow chain
    - Indicates configuration, architectural, or type safety issues
    - **Type Validation**: Triggered when source and target property types don't match in Key<T> mappings
- **Common scenarios**:
    - Missing workflow dependencies or configuration
    - State type mismatches between workflow steps
    - Workflow chain setup issues (e.g. calling `startWith` twice)
- **Handling strategy**:
    - These are typically developer errors that should be fixed in code
    - Log at ERROR level as they represent system design issues
    - Provide clear diagnostics to help identify the composition problem

##### ExecutionContextError

- **Purpose**: Adds execution metadata to a failure, including timing and workflow identifiers
- **When to use**: Automatically returned when a workflow fails, regardless of the underlying error type
- **Characteristics**:
    - Wraps the original `WorkflowError`
    - Contains a `WorkflowExecution` with start/end time and success status
    - Useful for logging and diagnostics on failures
- **Handling strategy**:
    - Log with the embedded execution metadata for better operational visibility
    - Preserve the wrapped error for user-facing or domain-specific handling

##### ChainError

- **Purpose**: Indicates an error occurred at the start of a composed use case chain
- **When to use**: Automatically returned when the initial workflow in a use case fails
- **Characteristics**:
    - Wraps the original `WorkflowError` (often `ExecutionContextError`)
    - Preserves the original error for structured inspection
- **Handling strategy**:
    - Inspect `error.error` to access the root cause
    - Treat as a chain-level failure and stop further processing

##### Error Handling Examples

The snippets below assume a suspend context (e.g., inside a suspend function or coroutine scope).

```kotlin
when (val result = useCase.execute(command)) {
  is Either.Right -> println("Success with ${result.value.events.size} events")
  is Either.Left -> when (val error = result.value) {
    is WorkflowError.ChainError -> {
      val root = error.error
      println("Chain failed: $root")
    }
    is WorkflowError.ExecutionContextError -> {
      val exec = error.execution
      println("Workflow ${exec.workflowId} failed after ${exec.endTime} with ${error.error}")
    }
    else -> println("Unhandled error: $error")
  }
}
```

##### Failure Behavior

Failures never return a `WorkflowResult`. Instead, they return a `WorkflowError` that may include execution metadata:

- `ExecutionContextError` contains timing and workflow identifiers for the failed step
- `ChainError` wraps failures from the initial workflow in a composed use case

##### Logging Execution Timing

Assumes a suspend context.

```kotlin
val result = useCase.execute(command)
result.fold(
  { error ->
    if (error is WorkflowError.ExecutionContextError) {
      val exec = error.execution
      val durationMs = java.time.Duration.between(exec.startTime, exec.endTime).toMillis()
      println("Workflow ${exec.workflowId} failed in ${durationMs}ms: ${error.error}")
    }
  },
  { success -> println("Workflow completed in ${success.context.executions.size} steps") }
)
```

##### Logging Chain Failures

Assumes a suspend context.

```kotlin
val result = useCase.execute(command)
result.fold(
  { error ->
    if (error is WorkflowError.ChainError) {
      val root = error.error
      println("Use case failed at the initial workflow: $root")
    }
  },
  { success -> println("Use case succeeded with ${success.events.size} events") }
)
```

#### Global Error Handling Strategies

##### Error Propagation

The Workflow framework uses Arrow's `Either` type to represent success or failure outcomes. This enables:

- **Short-circuiting**: When any workflow in a chain fails, subsequent workflows are not executed
- **Error preservation**: The original error is preserved and may be wrapped (e.g., `ChainError`)
- **Type safety**: Errors are handled in a type-safe manner

##### Error Transformation

Implement error transformation strategies to convert domain-specific errors to appropriate response types:

- **API responses**: Map workflow errors to appropriate HTTP status codes and response bodies
- **UI feedback**: Transform technical errors into user-friendly messages
- **Cross-cutting concerns**: Add metadata like error codes, timestamps, or correlation IDs

##### Error Recovery

Design workflows with error recovery in mind:

- **Retry workflows**: For transient failures, implement retry logic with backoff strategies
- **Compensation workflows**: Design workflows that can undo previous operations when later steps fail
- **Partial success**: Consider allowing use cases to complete with partial success when appropriate
- **Saga pattern**: For distributed transactions, implement compensation actions for each step

#### Best Practices

- **Be specific**: Use the most specific error type for each failure scenario
- **Meaningful messages**: Include actionable information in error messages
- **Preserve context**: Include relevant domain context in error objects
- **Layer appropriate**: Handle errors at the appropriate level of abstraction
- **Error boundaries**: Establish clear boundaries for error propagation and transformation
- **Fail fast**: Validate inputs early to prevent unnecessary processing
- **Audit errors**: Log errors consistently to enable error pattern analysis

#### Code Examples

The snippets below assume a suspend context (e.g., inside a suspend function or coroutine scope).

##### ValidationError Example

```kotlin
class ValidateOrderWorkflow(override val id: String) : Workflow<ValidateOrderCommand, OrderValidatedEvent>() {
  override suspend fun executeWorkflow(
    input: ValidateOrderCommand
  ): Either<WorkflowError, WorkflowResult> = either {
    // Validate order inputs
    ensure(input.items.isNotEmpty()) { 
      WorkflowError.ValidationError("Order must contain at least one item") 
    }

    ensure(input.totalAmount > 0) { 
      WorkflowError.ValidationError("Order amount must be greater than zero") 
    }

    // Create event and return result if validation passes
    val event = OrderValidatedEvent(/* ... */)
    WorkflowResult(listOf(event))
  }
}

// In API layer/controller
when (val result = orderUseCase.execute(command)) {
  is Either.Left -> when (val error = result.value) {
    is WorkflowError.ValidationError -> {
      logger.info("Order validation failed: ${error.message}")
      ResponseEntity.badRequest().body(ErrorResponse("VALIDATION_ERROR", error.message))
    }
    // Handle other error types...
  }
  is Either.Right -> ResponseEntity.ok(OrderCreatedResponse(/* ... */))
}
```

##### ExecutionError Example

```kotlin
class ProcessPaymentWorkflow(override val id: String) : Workflow<ProcessPaymentCommand, PaymentProcessedEvent>() {
  override suspend fun executeWorkflow(
    input: ProcessPaymentCommand
  ): Either<WorkflowError, WorkflowResult> = either {
    val customer = customerRepository.findById(input.customerId)

    // Check business rules
    if (customer.creditLimit < input.amount) {
      raise(WorkflowError.ExecutionError(
        "Payment exceeds customer's credit limit of ${customer.creditLimit}"
      ))
    }

    // Process payment if business rules pass
    val payment = paymentGateway.processPayment(input.orderId, input.amount)
    val event = PaymentProcessedEvent(/* ... */)
    WorkflowResult(listOf(event))
  }
}

// In error handling layer
when (val error = result.value) {
  is WorkflowError.ExecutionError -> {
    logger.warn("Business rule violation: ${error.message}")
    // Try alternative payment method or suggest corrective action
    notifyCustomerService("Payment failed: ${error.message}", orderId)
    ResponseEntity.status(HttpStatus.CONFLICT)
      .body(ErrorResponse("BUSINESS_RULE_VIOLATION", error.message))
  }
  // Other error types...
}
```

##### ExceptionError Example

```kotlin
class CheckInventoryWorkflow(override val id: String) : Workflow<CheckInventoryCommand, InventoryVerifiedEvent>() {
  override suspend fun executeWorkflow(
    input: CheckInventoryCommand
  ): Either<WorkflowError, WorkflowResult> = either {
    try {
      // External service call that might fail
      val inventoryStatus = inventoryService.checkAvailability(input.items)

      val event = InventoryVerifiedEvent(/* ... */)
      WorkflowResult(listOf(event))
    } catch (e: InventoryServiceException) {
      raise(WorkflowError.ExceptionError(
        "Failed to check inventory availability", e
      ))
    } catch (e: Exception) {
      raise(WorkflowError.ExceptionError(
        "Unexpected error during inventory check", e
      ))
    }
  }
}

// In error handling middleware
private val retryableErrorTypes = setOf(
  "CONNECTION_TIMEOUT", "SERVICE_UNAVAILABLE"
)

when (val error = result.value) {
  is WorkflowError.ExceptionError -> {
    logger.error("System error during operation", error.ex)

    // Determine if error is retryable
    if (error.ex is ServiceException && 
        retryableErrorTypes.contains(error.ex.errorCode)) {
      // Add to retry queue
      retryQueue.scheduleRetry(command, RetryPolicy.EXPONENTIAL_BACKOFF)
      ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ErrorResponse("RETRY_SCHEDULED", "Operation will be retried automatically"))
    } else {
      // Non-retryable system error
      ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ErrorResponse("SYSTEM_ERROR", "An unexpected error occurred"))
    }
  }
  // Other error types...
}
```

##### CompositionError Example

```kotlin
// In application startup
when (error) {
  is WorkflowError.CompositionError -> {
    logger.error("Critical configuration error: ${error.message}", error.ex)
    // This is a developer error, so fail fast in development
    if (environment.isDevelopment) {
      throw error.ex
    } else {
      // In production, use fallback configuration if possible
      useBackupConfiguration()
    }
  }
  // Other error handling...
}
```

By leveraging the structured error types provided by the Workflow framework, you can create robust error handling strategies that improve system reliability and user experience.

## Best Practices

### Designing Effective Workflows

Effective workflows are the foundation of a maintainable, scalable business logic implementation. Follow these principles to create workflows that are focused, reusable, and easy to understand:

#### Single Responsibility Principle

Each workflow should perform one cohesive task with clear boundaries:

- **Narrow Focus**: A workflow should address a single business concern (e.g., `ValidateUserDataWorkflow`, not `ProcessUserWorkflow`).
- **Clear Input/Output Contract**: Define explicit command/query inputs and event outputs that clearly represent the workflow's purpose.
- **Avoid Side Tasks**: If a workflow starts handling multiple concerns, it's a sign to split it into separate workflows.

#### Isolation and Independence

Workflows should be self-contained and minimize dependencies:

- **No Direct Workflow Dependencies**: Workflows should never directly call other workflows; composition happens at the UseCase level.
- **Minimal External Dependencies**: Inject only the services necessary for the workflow's core responsibility.
- **Context Sharing**: Use WorkflowContext for cross-cutting concerns rather than tight coupling.

#### Statelessness and Determinism

Workflows should be predictable and free of side effects:

- **Input-Driven Behavior**: A workflow's behavior should be determined solely by its input parameters.
- **Consistent Results**: Given the same input, a workflow should produce the same output (or error) every time.
- **Explicit Side Effects**: Any side effects (database writes, external API calls) should be explicit and documented.

#### Granular Error Handling

Use the appropriate error type for each failure scenario:

- **ValidationError**: For input validation failures (e.g., missing required fields, invalid formats).
- **ExecutionError**: For business rule violations (e.g., insufficient inventory, credit limit exceeded).
- **ExceptionError**: For technical or system failures (e.g., database connection issues, external service failures).

#### Performance Considerations

- **Resource Usage**: Be mindful of memory and CPU usage, especially for workflows that process large datasets.
- **External Calls**: Minimize network calls and consider timeouts for external dependencies.
- **Parallel-Safe Design**: Ensure workflows can be safely executed in parallel contexts when needed.

#### Testing-Friendly Design

- **Mockable Dependencies**: Design workflows to accept interfaces rather than concrete implementations for easier testing.
- **Isolated Business Logic**: Keep business rules separate from infrastructure concerns to simplify unit testing.
- **Deterministic Behavior**: Avoid non-deterministic elements like random values or current time unless explicitly injected.

### Structuring Complex Use Cases

Complex business processes often require sophisticated orchestration. Here are patterns and techniques for organizing complex use cases effectively:

#### Layered Workflow Composition

Break down complex processes into multiple layers of workflows:

- **Core Domain Workflows**: Implement fundamental business operations (e.g., `ValidateOrderWorkflow`, `ProcessPaymentWorkflow`).
- **Composite Workflows**: Create higher-level workflows that coordinate related domain operations into coherent sub-processes.
- **Orchestration Use Cases**: Top-level use cases that compose the complete business process from these building blocks.

#### Effective Parallel Processing

Use parallel execution to optimize performance when workflows are independent:

```kotlin
parallel {
  then(CheckInventoryWorkflow("check-inventory"))
  then(VerifyCustomerCreditWorkflow("verify-credit"))
  then(ReserveShippingCapacityWorkflow("reserve-shipping"))
}
```

Considerations:
- Only parallelize truly independent workflows that don't rely on each other's outputs
- Be aware of resource contention (database connections, external API rate limits)
- Consider adding timeout handling for operations that may take an unpredictable amount of time

When you need a typed aggregate event from parallel branches, use `parallelJoin`:

```kotlin
parallelJoin(loadCatalogs, fetchSerp) { catalogs, serp ->
  ReadyForConversion(
    searchId = catalogs.searchId,
    criteria = catalogs.criteria,
    catalogs = catalogs.catalogs,
    serpItineraries = serp.serpItineraries,
  )
}
```

#### Conditional Execution Patterns

Implement sophisticated business rules using conditional workflow execution:

##### Decision Branching

```kotlin
thenIf(
  workflow = SendPremiumShippingWorkflow("premium-shipping"),
  predicate = { result -> 
    result.context.getTypedData<Boolean>("isPremiumCustomer") == true ||
    (result.getFromEvent(OrderValidatedEvent::totalAmount) ?: 0.0) > 100.0
  }
)
```

##### State-Based Processing

```kotlin
// Execute different workflows based on payment type
when (paymentType) {
  "credit" -> then(ProcessCreditCardWorkflow("process-cc"))
  "paypal" -> then(ProcessPayPalWorkflow("process-paypal"))
  "crypto" -> then(ProcessCryptoWorkflow("process-crypto"))
}
```

#### Complex Data Mapping Strategies

For use cases that materialize derived structures:

- **State Transformation Workflows**: Use workflows dedicated to reshaping the current `WorkflowState` into richer variants; each workflow emits its own events and returns the new typed state you need for downstream logic.
  ```kotlin
  then(CalculateInvoiceWorkflow("calculate-invoice"))
  ```

- **Context Enrichment**: Store intermediate calculations or metadata in `WorkflowContext` to make them available later in the chain.
  ```kotlin
  WorkflowResult(
    listOf(event),
    context.addData("taxCalculation", taxDetails)
          .addData("shippingCost", shippingCost)
  )
  ```

#### Long-Running Process Patterns

For processes that span significant time periods:

- **Process Checkpointing**: Design workflows to emit events at key process milestones
- **Resumable Workflows**: Create use cases that can pick up from specific points in the process
- **Process Status Tracking**: Use the workflow context to maintain process state information

#### Error Recovery Strategies

Implement resilient processes with sophisticated error handling:

- **Compensating Workflows**: Design workflows that can undo or compensate for previous steps on failure
  ```kotlin
  // If payment succeeds but shipping fails, execute refund workflow
  when (result) {
    is Either.Left -> {
      if (context.getTypedData<Boolean>("paymentProcessed") == true) {
        refundUseCase.execute(CreateRefundCommand(orderId, amount))
      }
    }
  }
  ```

- **Partial Success Handling**: Consider whether parts of a process can succeed even if others fail

- **Retry Policies**: Implement sophisticated retry logic for transient failures
  ```kotlin
  // Example of retry logic for an external service call
  retry(maxAttempts = 3, backoffMs = 1000) {
    externalPaymentService.processPayment(paymentDetails)
  }
  ```

#### Domain Event Sourcing Integration

For systems using event sourcing:

- **Event Publishing Workflows**: Create dedicated workflows for publishing domain events to an event store
- **Event-Driven Workflows**: Design workflows that react to domain events from other bounded contexts
- **Event Stream Processing**: Implement workflows that process streams of related events
