# Workflow Reference

This document covers the public API. Start with the [README](../Readme.md) if you want the shortest path to usage.

## Core Types

| Type | Purpose |
| --- | --- |
| `UseCase<I, E, O>` | Public callable application use case. Accepts input `I`, returns `Either<E, O>`. |
| `Pipeline<I, E, S>` | Immutable, typed chain of steps built inside `useCase { }`. `S` is the current state type. |
| `Workflow<I, O>` | A focused workflow step from input state `I` to output state `O`. |
| `WorkflowStepScope` | Receiver of a step: raise errors, read `context`, `emit` events, `put` context data. |
| `WorkflowResult<S>` | State, events, and context: the outcome of a step run on its own, and the receiver of `thenIf` predicates. |
| `ResultScope<S>` | Receiver of `result { }`: final events and context, plus `requireEvent<T>()`. |
| `Event` | Domain event emitted during execution. `BaseEvent` fills in `id` and `timestamp`. |
| `WorkflowContext` | Immutable, typed metadata that flows through the use case. |
| `Key<T>` | Typed key for a `WorkflowContext` value. |
| `WorkflowError` | Error interface. The library's errors are nested in it; applications can implement it. |
| `UseCaseSummary` | Per-invocation summary passed to `onSummary` listeners. |

Any type can be use case input or workflow state. The old `UseCaseCommand` and `WorkflowState` marker interfaces are deprecated and no longer needed; remove them from your types.

## `useCase`

Use `useCase` to build an application-facing use case. Its block starts a pipeline with `startWith`, chains steps, and ends with `result { }`.

```kotlin
val placeOrder: UseCase<PlaceOrder, ApplicationError, OrderDto> =
  useCase(name = "PlaceOrder", errors = ::toApplicationError) {
    startWith { command -> OrderDraft(command.id, command.items) }
      .then(validateOrder)
      .then(priceOrder)
      .result { order -> order.toDto() }
  }
```

If callers can work with raw workflow errors, omit `errors`. The use case then returns `WorkflowError`:

```kotlin
val placeOrder: UseCase<PlaceOrder, WorkflowError, OrderDto> =
  useCase(name = "PlaceOrder") {
    startWith { command -> OrderDraft(command.id, command.items) }
      .then(validateOrder)
      .result { order -> order.toDto() }
  }
```

Kotlin cannot infer the input type from the block, so either declare the expected type as above or pass type arguments: `useCase<PlaceOrder, OrderDto> { ... }`, or `useCase<PlaceOrder, ApplicationError, OrderDto>(errors = ...) { ... }`.

`name` is a stable operational name used in logs and summaries. It defaults to `"UseCase"`. Do not include user or request data.

Call a use case with its input, and optionally a `WorkflowContext`:

```kotlin
val result: Either<ApplicationError, OrderDto> = placeOrder(command)
val traced = placeOrder(command, WorkflowContext.of(WorkflowContext.CORRELATION_ID to "corr-123"))
```

`UseCase` is a `fun interface`, so you can also implement it directly.

### Comprehensive Example

This example shows the pipeline methods together. Each method returns a new `Pipeline` whose state type is the output of the last step, so the compiler checks that every step accepts the previous step's output.

```kotlin
val registerUser: UseCase<RegisterUserCommand, RegisterUserError, UserDto> =
  useCase(name = "RegisterUser", errors = ::toRegisterUserError) {
    startWith { command ->
      ensure(command.email.isNotBlank()) { WorkflowError.ValidationError("Email is required") }
      RegistrationDraft(command.email, command.password)
    }
      .then(ValidateRegistration())                       // RegistrationDraft -> ValidRegistration
      .thenIf(ApplyInviteCode()) { context[INVITE_CODE] != null } // ValidRegistration -> ValidRegistration
      .parallel(RecordSignupAttempt(), PublishRegistrationStarted())
      .parallelJoin(CreateUser(users), CreateUserProfile(profiles), policy = ParallelErrorPolicy.FailFast) { user, profile ->
        RegisteredUser(id = user.id, email = email, profileId = profile.id) // `this` is the ValidRegistration
      }
      .thenLaunch(SendWelcomeEmail(), timeout = 2.seconds)
      .awaitLaunched()
      .result { user -> UserDto(id = user.id, email = user.email) }
      .logging {
        success { (user) -> field("userId", user.id) }
        success { result -> field("eventId", result.events.lastEvent<UserRegisteredEvent>()?.id) }
        failure { error ->
          field("reason", error.reason)
          field("retryable", error.retryable)
        }
      }
  }
```

The flow is:

- `startWith` validates public input and creates the initial state.
- `then` runs the next state-transforming workflow.
- `thenIf` runs a workflow only when the predicate matches the current state.
- `parallel` runs side-effect workflows concurrently and keeps the current state.
- `parallelJoin` runs state-producing workflows concurrently and merges their outputs into a new state.
- `thenLaunch` starts side-effect work without blocking the main chain; `awaitLaunched` merges launched events and context.
- `result { }` projects the public output from the final state, events, and context.
- `logging { }` adds explicit fields to the finish log without changing the use case result.

`ensure` and `ensureNotNull` are Arrow functions; import them from `arrow.core.raise`.

## Pipeline Methods

### `startWith`

Converts public input into the first workflow state:

```kotlin
startWith { command: RegisterUserCommand ->
  ensure(command.email.isNotBlank()) { WorkflowError.ValidationError("Email is required") }
  RegistrationDraft(command.email, command.password)
}
```

The block runs with an Arrow `Raise<WorkflowError>` receiver. Return the state directly, and fail with `ensure`, `ensureNotNull`, `raise`, or `bind()`. Exceptions thrown by the block become `WorkflowError.ExceptionError`.

### `then`

Runs a workflow on the current state. Its output becomes the new state:

```kotlin
.then(ValidateRegistration())
.then(CreateUser(users))
```

The workflow must be a `Workflow<S, O>` where `S` is the current state type. A mismatch is a compile error.

### `thenIf`

Runs a workflow only when a predicate matches:

```kotlin
.thenIf(SendWelcomeEmail()) { user -> user.marketingOptIn && context[FeatureFlags.WELCOME_EMAIL] == true }
```

The predicate receives the current state as its parameter and a `WorkflowResult<S>` as its receiver, so `state`, `events`, and `context` are available. Because the step may be skipped, it must be a `Workflow<S, S>`. Keep predicates small and side-effect free. Exceptions thrown by the predicate become `WorkflowError.ExceptionError`.

### `parallel`

Runs side-effect workflows concurrently on the current state and keeps that state:

```kotlin
.parallel(SendReceiptEmail(), PublishPaymentEvent())
.parallel(SendReceiptEmail(), PublishPaymentEvent(), policy = ParallelErrorPolicy.FailFast)
```

Each workflow must accept `S`; its output is ignored. Events are merged in parameter order, and context from every branch is merged. A failing branch fails the use case.

### `parallelJoin`

Runs state-producing workflows concurrently on the current state and merges their outputs into a new state:

```kotlin
.parallelJoin(CalculateSubtotal(), CalculateTax()) { subtotal, tax ->
  PricedOrder(order = this, subtotal = subtotal.amount, tax = tax.amount) // `this` is the current state
}
```

There are overloads for 2 through 8 workflows. The current state is the receiver (`this`) of the merge block, so the new state can keep fields of the old one.

`policy` is a named parameter that defaults to `ParallelErrorPolicy.WaitAll`, which waits for every branch and fails with the first error by parameter order. `ParallelErrorPolicy.FailFast` cancels the remaining branches on the first failure and fails with the first error by parameter order among the branches that finished.

Branches run with the same starting context. Each branch only merges the context keys it changed, and branches that succeeded are kept (their events count in the summary) even when another branch fails.

`parallelJoin` is also a top-level function that returns a `Workflow<I, R>`. Use it to name a join or reuse it:

```kotlin
val priceOrder: Workflow<ValidatedOrder, PricedOrder> =
  parallelJoin(CalculateSubtotal(), CalculateTax()) { subtotal, tax ->
    PricedOrder(order = this, subtotal = subtotal.amount, tax = tax.amount) // `this` is the input
  }

startWith { command -> command.toValidatedOrder() }
  .then(priceOrder)
```

### `thenLaunch` And `awaitLaunched`

`thenLaunch` starts a side-effect workflow without waiting for it. The main chain continues with the same state:

```kotlin
.thenLaunch(SendAuditEvent())
.thenLaunch(SendWelcomeEmail(), timeout = 2.seconds)
.then(ContinueMainFlow())
.awaitLaunched()
```

`timeout` is a `kotlin.time.Duration?` and defaults to no timeout.

`awaitLaunched()` waits for everything launched so far and merges its events and context. Use it when later steps or the result need them. Work that is still running when the pipeline ends is awaited and merged before `result { }` runs, so `awaitLaunched()` at the very end is not required.

A launched failure or timeout never fails the use case. Each one is recorded as a `LaunchedFailureEvent` (with `workflowId` and `message`) and as a message in `context[WorkflowContext.LAUNCHED_FAILURES]`.

Launched work runs inside the use case invocation. Cancelling the caller cancels it too. When launched work is merged, only the context keys it changed are applied, so it never undoes a change a later step made.

## Result Projection

`result { }` is the last call in the pipeline. It projects the final state into the public output:

```kotlin
.result { user -> UserDto(user.id, user.email) }
```

The block's receiver is a `ResultScope<S>`, which is an Arrow `Raise<WorkflowError>` and also exposes:

| Member | Purpose |
| --- | --- |
| `events` | Every event emitted by the pipeline, in order. |
| `context` | The final context. |
| `result` | The final `WorkflowResult<S>` (state, events, context). |
| `requireEvent<T>()` | The last event of type `T`, or raises `WorkflowError.CompositionError` when none was emitted. |

Combine them as needed:

```kotlin
.result { order ->
  OrderReceipt(order.id, order.total, events.lastEvent<ShipmentPrepared>()?.trackingNumber)
}

.result { requireEvent<UserRegisteredEvent>() }
```

Exceptions thrown by the block become `WorkflowError.ExceptionError`.

## Writing Workflows

Implement `Workflow<I, O>` for a step with dependencies:

```kotlin
class ReserveInventory(private val inventory: Inventory) : Workflow<ValidatedOrder, ReservedOrder> {
  override val id = "reserve-inventory"

  override suspend fun WorkflowStepScope.execute(input: ValidatedOrder): ReservedOrder {
    val reservation = ensureNotNull(inventory.reserve(input.items)) { OrderError.OutOfStock }
    emit(InventoryReserved(input.orderId, reservation.id))
    return ReservedOrder(input, reservation)
  }
}
```

Use the `workflow` function for a step that needs no class of its own:

```kotlin
val validateOrder = workflow<OrderDraft, ValidatedOrder>("validate-order") { draft ->
  ensure(draft.items.isNotEmpty()) { WorkflowError.ValidationError("Order has no items") }
  emit(OrderValidated(draft.orderId))
  ValidatedOrder(draft.orderId, draft.items)
}
```

`id` is a stable identifier used in execution history, logs, and summaries.

`WorkflowStepScope` is an Arrow `Raise<WorkflowError>`. It also provides:

| Member | Purpose |
| --- | --- |
| `context` | The context as earlier steps left it, plus anything this step has `put`. |
| `emit(event)` | Records a domain event. |
| `put(key, value)` | Adds typed data to the context seen by later steps. |

These members are safe to call from coroutines launched inside the step. Exceptions thrown by a step become `WorkflowError.ExceptionError`.

### Running A Step On Its Own

Call a workflow directly to unit-test it:

```kotlin
val result: Either<WorkflowError, WorkflowResult<ValidatedOrder>> =
  validateOrder(OrderDraft(orderId, items), WorkflowContext())
```

The context argument is optional. The `WorkflowResult` holds the new `state`, the `events` the step emitted, and the `context` it produced.

## Error Handling

`WorkflowError` is an open interface with two properties:

| Property | Purpose |
| --- | --- |
| `code` | Stable, non-sensitive identifier used in logs and summaries. Defaults to the class name. |
| `message` | Human-readable description. May contain user data. Defaults to `code`. |

The library's own errors are nested in it:

| Error | Use for |
| --- | --- |
| `ValidationError(message)` | Input or state validation failure. |
| `ExecutionError(message)` | Expected technical failure while running a step. Also used for `thenLaunch` timeouts. |
| `DomainError(code, message, metadata)` | Business rule failure with a stable code and optional metadata. |
| `ExceptionError(message, ex)` | Exception thrown by a step, `startWith`, a predicate, or `result { }`. |
| `CompositionError(message)` | A use case that cannot produce its result, such as a missing event in `requireEvent`. |

Applications can implement `WorkflowError` with their own sealed types and raise them from steps:

```kotlin
sealed interface OrderError : WorkflowError {
  data object EmptyOrder : OrderError
  data class PaymentDeclined(val reason: String) : OrderError
}
```

Errors are not wrapped. The `errors` mapper receives the error exactly as it was raised, so you can match on your own types:

```kotlin
private fun toApiError(error: WorkflowError): ApiError =
  when (error) {
    OrderError.EmptyOrder -> ApiError(400, "empty-order")
    is OrderError.PaymentDeclined -> ApiError(402, "payment-declined")
    is WorkflowError.ValidationError -> ApiError(400, "invalid")
    else -> ApiError(500, "internal")
  }
```

Execution short-circuits on failure. Later steps do not run after a step raises.

The `errors` mapper should be total and non-throwing. If it throws, that exception propagates to the caller.

## Workflow Context

`WorkflowContext` is immutable, typed metadata that flows through the use case. Values are stored under `Key<T>`. Keys compare by identity, so declare each key once and share it:

```kotlin
object Keys {
  val TENANT = Key<TenantId>("tenant")
  val FLAGS = Key<Set<String>>("flags")
}
```

The name is diagnostic only. Because the value type lives in the type parameter, generic types such as `Set<String>` keep their full type.

Build and read a context:

```kotlin
val context = WorkflowContext.of(
  WorkflowContext.CORRELATION_ID to "corr-123",
  Keys.TENANT to TenantId("tenant-a"),
)

val tenant: TenantId? = context[Keys.TENANT]
val withFlags = context.with(Keys.FLAGS, setOf("beta"))
val merged = context + otherContext // `otherContext` wins on conflicts
val hasTenant = Keys.TENANT in context
```

`key to value` builds a typed `Key.Entry`, so `WorkflowContext.of(Keys.TENANT to 42)` does not compile.

Inside a step, read `context[key]` and add values with `put(key, value)`.

Built-in keys:

| Key | Value |
| --- | --- |
| `WorkflowContext.CORRELATION_ID` | `Key<String>`. Reported in `UseCaseSummary.correlationId`. |
| `WorkflowContext.LAUNCHED_FAILURES` | `Key<List<String>>`. Messages for launched side effects that failed or timed out. |

Keep context values operational. Avoid storing domain payloads or sensitive data in context just to make them easy to reach.

## Logging And Summaries

Use cases emit debug-level SLF4J logs before and after execution, on the `io.liquidsoftware.workflow.UseCase` logger:

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

The library does not log input, state, event payloads, output, or error messages.

## Use Case Logging DSL

Global configuration should be set once at application startup before invoking use cases:

```kotlin
useCaseLoggingGlobalConfig {
  outcomeStyle(OutcomeStyle.Plain)
  defaultSummaryFields(DefaultSummaryFields.Enabled)
  onSummary { summary -> metrics.record(summary) }
}
```

Options:

| Setting | Values | Default |
| --- | --- | --- |
| `outcomeStyle` | `OutcomeStyle.Plain`, `OutcomeStyle.AnsiColor` | `OutcomeStyle.Plain` |
| `defaultSummaryFields` | `DefaultSummaryFields.Enabled`, `DefaultSummaryFields.Disabled` | `DefaultSummaryFields.Enabled` |
| `onSummary` | `(UseCaseSummary) -> Unit` | none |

`onSummary` adds a listener that is called with a `UseCaseSummary` after every use case invocation, whatever the log level. Each call adds another listener; `clearSummaryListeners()` removes them all. Listeners run on the caller's coroutine; exceptions they throw are logged and ignored.

Log fields render as stable `key=value` pairs.

Per-use-case logging fields are explicit. Chain `.logging { }` after `result { }`:

```kotlin
.result { user -> user.toDto() }
.logging {
  success { result -> field("userId", result.state.id) }
  success { (_, _, context) -> field("tenant", context[Keys.TENANT]) }
  failure { error -> field("reason", error.reason) }
}
```

- `success` receives the final `WorkflowResult<S>` (state, events, context) and can be added more than once.
- `failure` receives the mapped public error `E` and can be set once.

Fields are only collected when debug logging is enabled, so field blocks cost nothing otherwise.

The library never logs whole inputs, states, events, outputs, or errors by default. Log only stable fields that are safe for your runtime environment.
Explicit fields cannot be blank or use the reserved built-in names `name`, `outcome`, `durationMs`, `events`, or `workflows`. Invalid fields and thrown logging lambdas are reported as logging projection failures and do not change the use case result.

### `UseCaseSummary`

| Property | Purpose |
| --- | --- |
| `useCaseName` | The configured name. |
| `succeeded` | Whether the invocation succeeded. |
| `duration` | Total duration. |
| `eventsCount` | Number of events emitted, including on failure (events from steps that completed before the failure, and launched failures). |
| `executions` | `WorkflowExecution`s for every step run, including parallel and launched ones. |
| `rootError` | `RootErrorInfo` for a failed invocation, otherwise `null`. |
| `launchedFailures` | Messages from `WorkflowContext.LAUNCHED_FAILURES`. |
| `correlationId` | Value of `WorkflowContext.CORRELATION_ID`, if set. |

`RootErrorInfo` has the error's `type` (class name), `code`, `message`, and the `workflowId` of the failing step when the failure came from a step. Launched side effects never count as the failing step.

Error messages are redacted by default because they may contain user data, so `rootError.message` is `null`. Use `withErrorMessages()` to get a copy that includes it:

```kotlin
useCaseLoggingGlobalConfig {
  onSummary { summary -> log.info(summary.withErrorMessages().toString()) }
}
```

Only use `withErrorMessages()` when those messages are known to be safe for your environment.
