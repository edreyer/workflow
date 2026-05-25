# Workflow Reference

This document covers the public DSL and lower-level APIs. Start with the [README](../Readme.md) if you want the shortest path to usage.

## Core Types

| Type | Purpose |
| --- | --- |
| `UseCase<I, E, O>` | Public callable application use case. Accepts input `I`, returns `Either<E, O>`. |
| `WorkflowUseCase<C>` | Lower-level workflow-native execution model. Returns detailed state, event, and context data. |
| `UseCaseCommand` | Marker interface for public use case input. |
| `WorkflowState` | Marker interface for typed state passed between workflow steps. |
| `Workflow<I, O>` | A focused workflow step from input state `I` to output state `O`. |
| `WorkflowResult<S>` | Output of a workflow step: state, events, and context. |
| `UseCaseResult<S>` | Detailed output of a workflow use case: final state, all events, and context. |
| `Event` | Domain event emitted during workflow execution. |
| `WorkflowContext` | Immutable metadata and execution-history container. |
| `WorkflowError` | Workflow-native error model. |

## `useCase`

Use `useCase` for application-facing boundaries.

```kotlin
val useCase: UseCase<Input, ApplicationError, Output> =
  useCase(
    name = "Example",
    errors = { error -> error.toApplicationError() },
  ) {
    startWith { input -> Either.Right(InitialState(input.id)) }
    then(FirstWorkflow())
    then(SecondWorkflow())
    result(state<FinalState>()) { state -> state.toOutput() }
  }
```

If callers can work with raw workflow errors, omit `errors`:

```kotlin
val useCase: UseCase<Input, WorkflowError, Output> =
  useCase(name = "Example") {
    startWith { input -> Either.Right(InitialState(input.id)) }
    then(FirstWorkflow())
    result(state<FinalState>()) { state -> state.toOutput() }
  }
```

`result(...)` is required and can be called only once.

## `workflowUseCase`

Use `workflowUseCase` for lower-level code that needs workflow-native details:

```kotlin
val workflow: WorkflowUseCase<Input> =
  workflowUseCase(name = "Example") {
    startWith { input -> Either.Right(InitialState(input.id)) }
    then(FirstWorkflow())
  }

val detailed: Either<WorkflowError, UseCaseResult<WorkflowState>> =
  workflow.executeDetailed(input)
```

`WorkflowUseCase` also provides:

| Method | Purpose |
| --- | --- |
| `executeDetailed(command, context)` | Return final state, all events, and context. |
| `execute(command)` | Return emitted events only. |
| `executeForState<S>(command, context)` | Return final state as `S`, or a composition error. |
| `executeForEvent<E>(command, context)` | Return the last emitted event of type `E`, or a composition error. |
| `executeProjected(command, context) { ... }` | Custom projection from detailed result. |

Most application code should prefer `UseCase<I, E, O>` built with `useCase`.

## DSL Methods

### `startWith`

Initializes the pipeline state from public input:

```kotlin
startWith { input: RegisterUserCommand ->
  Either.Right(RegistrationDraft(input.email, input.password))
}
```

Rules:

- Must be called exactly once.
- Must be the first DSL method called.
- Exceptions thrown by the mapper are converted to `WorkflowError.ExceptionError`.

### `then`

Runs a workflow after the previous state is available:

```kotlin
then(ValidateRegistration())
then(CreateUser(users))
```

The workflow's input state type must match the current state type.

### `thenIf`

Runs a workflow only when a predicate matches the current result:

```kotlin
thenIf(SendWelcomeEmail()) { result ->
  result.context.get(FeatureFlags.SEND_WELCOME_EMAIL) == true
}
```

Use this for business branching. Keep predicates small and side-effect free.

### `parallel`

Runs independent side-effect workflows concurrently and preserves the current state:

```kotlin
parallel<PaidOrder> {
  then(SendReceiptEmail())
  then(PublishPaymentEvent())
}
```

Use this when each branch is independent and the main state should not be replaced by branch output.

### `parallelJoin`

Runs independent state-producing workflows concurrently and merges their outputs into a new state:

```kotlin
parallelJoin(
  CalculateSubtotal(),
  CalculateTax(),
) { subtotal, tax ->
  PricedOrder(subtotal.orderId, subtotal.amount, tax.amount)
}
```

There are overloads for 2 through 8 workflows. Use the overload with `ParallelErrorPolicy` when you need non-default failure behavior.

### `thenLaunch` And `awaitLaunched`

`thenLaunch` starts side-effect work without waiting for it before the main chain continues:

```kotlin
thenLaunch(SendAuditEvent())
then(ContinueMainFlow())
awaitLaunched()
```

Use `awaitLaunched()` if later steps or the final result need launched workflow events/context. Launched failures are captured in context and do not fail the main chain when awaited.

## Result Projection

### State Projection

Use `state<T>()` when output comes from the final state:

```kotlin
result(state<RegisteredUser>()) { state ->
  UserDto(state.id, state.email)
}
```

If the final state is not `T`, the use case returns `WorkflowError.CompositionError`.

### Event Projection

Use `event<T>()` when output comes from the last emitted event of type `T`:

```kotlin
result(event<UserRegisteredEvent>()) { event ->
  event
}
```

If no event of type `T` was emitted, the use case returns `WorkflowError.CompositionError`.

### Custom Projection

Use a custom projection when output needs a combination of state, events, or context:

```kotlin
result(
  UseCaseResultProjection { result, useCaseName ->
    result.requireState<RegisteredUser>(useCaseName).map { state ->
      UserDto(state.id, state.email)
    }
  }
)
```

Exceptions thrown by projection mappers are converted to `WorkflowError.ExceptionError`.

## Error Handling

`WorkflowError` is the workflow-native error model:

| Error | Use for |
| --- | --- |
| `ValidationError` | Input or state validation failure. |
| `ExecutionError` | Expected technical failure while running a step. |
| `DomainError` | Domain-specific failure with a stable code and optional metadata. |
| `ExceptionError` | Unexpected exception captured by the library. |
| `CompositionError` | Invalid workflow composition or missing projected state/event. |
| `ExecutionContextError` | A workflow failure with workflow execution metadata attached. |
| `ChainError` | Failure wrapped at the chain level. |

Workflow execution short-circuits on failure. Later steps do not run after an `Either.Left`.

Map errors at the public boundary:

```kotlin
useCase(
  name = "RegisterUser",
  errors = { error -> error.toApplicationError() },
) {
  // ...
}
```

The `errors` mapper should be total and non-throwing. If it throws, that exception is not converted into `Either`.

## Workflow Context

`WorkflowContext` carries metadata and execution history through the chain. Prefer typed keys:

```kotlin
val tenantId = Key.string("tenantId")

val context =
  WorkflowContext()
    .addData(WorkflowContext.CORRELATION_ID, "corr-123")
    .addData(tenantId, "tenant-a")

val result = useCase(input, context)
```

Inside a workflow:

```kotlin
val correlationId = context.get(WorkflowContext.CORRELATION_ID)
```

Keep context values operational. Avoid storing domain payloads or sensitive data in context just to make them easy to reach.

## Logging And Summaries

`WorkflowUseCase` emits debug-level SLF4J logs before and after execution:

```text
UseCase invocation started name=RegisterUser
UseCase invocation finished name=RegisterUser outcome=success durationMs=12 events=1 workflows=2
```

Logged fields are intentionally limited to operational metadata:

- configured use case name
- success/failure outcome
- duration
- event count
- workflow count

The library does not log command input, state, event payloads, projected output, or error messages.

`toSummary(...)` can build structured logging or metrics summaries:

```kotlin
val summary = detailedResult.toSummary(useCaseName = "RegisterUser")
```

Error messages are redacted by default because they may contain user data:

```kotlin
val summary = detailedResult.toSummary(
  useCaseName = "RegisterUser",
  includeErrorMessages = false,
)
```

Only set `includeErrorMessages = true` when those values are known to be safe for your environment.
