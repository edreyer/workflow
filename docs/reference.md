# Workflow Reference

This document covers the public API. Start with the [README](../Readme.md) if you want the shortest path to usage.

## Core Types

| Type | Purpose |
| --- | --- |
| `UseCase<I, E, O>` | Public callable application use case. Accepts input `I`, returns `Either<E, O>`. |
| `Pipeline<I, E, S>` | Immutable, typed chain of steps built inside `useCase { }`. `E` is the error type its steps raise; `S` is the current state type. |
| `Workflow<I, O, E>` | A named step from input state `I` to output state `O` that can raise `E`, built with `workflow(id) { }` or inline with `.then(id) { }`. |
| `WorkflowStepScope<E>` | Receiver of a step: raise `E`, read `context`, `emit` events. |
| `WorkflowResult<S>` | State, events, and context: the outcome of a step run on its own, and the receiver of `thenIf` predicates. |
| `ResultScope<S, E>` | Receiver of `result { }`: final events and context, plus `requireEvent<T>()`. |
| `Event` | Marker for a domain event emitted during execution. It requires no members; extend `BaseEvent` to get a generated `id` and `timestamp`. |
| `WorkflowContext` | Immutable, typed metadata that flows through the use case. |
| `Key<T>` | Typed key for a `WorkflowContext` value. |
| `Failure<E>` | Why a use case or step failed: `Failure.Raised(error)` or `Failure.Unexpected(cause)`. |
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

The mapper's parameter type, such as `Failure<OrderError>`, sets the error type every step must raise. If callers can work with the failure itself, omit `errors`. The use case then returns `Failure<OrderError>`:

```kotlin
val placeOrder: UseCase<PlaceOrder, Failure<OrderError>, OrderDto> =
  useCase(name = "PlaceOrder") {
    startWith { command -> OrderDraft(command.id, command.items) }
      .then(validateOrder)
      .result { order -> order.toDto() }
  }
```

Kotlin cannot infer the input type from the block, so either declare the expected type as above or pass type arguments: `useCase<PlaceOrder, OrderError, OrderDto>("PlaceOrder") { ... }`, or `useCase<PlaceOrder, OrderError, ApplicationError, OrderDto>("PlaceOrder", errors = ...) { ... }`. When `errors` is a function reference or a lambda with a typed parameter, the error type is inferred from it.

`name` is required: a stable operational name used in logs and summaries. Do not include user or request data.

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
      ensure(command.email.isNotBlank()) { RegistrationError.EmailRequired }
      RegistrationDraft(command.email, command.password)
    }
      .then(validateRegistration)                       // RegistrationDraft -> ValidRegistration
      .thenIf(applyInviteCode) { context[INVITE_CODE] != null } // ValidRegistration -> ValidRegistration
      .parallel(recordSignupAttempt, publishRegistrationStarted)
      .parallelJoin(createUser(users), createUserProfile(profiles), policy = ParallelErrorPolicy.FailFast) { user, profile ->
        RegisteredUser(id = user.id, email = email, profileId = profile.id) // `this` is the ValidRegistration
      }
      .thenLaunch(sendWelcomeEmail, timeout = 2.seconds)
      .awaitLaunched()
      .result { user -> UserDto(id = user.id, email = user.email) }
      .logging {
        success { (user) -> field("userId", user.id) }
        success { result -> field("eventId", result.events.lastEvent<UserRegisteredEvent>()?.id) }
        failure { failure ->
          field("unexpected", failure is Failure.Unexpected)
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
- `thenLaunch` starts side-effect work without blocking the main chain; `awaitLaunched` merges launched events and records launched failures.
- `result { }` projects the public output from the final state, events, and context.
- `logging { }` adds explicit fields to the finish log without changing the use case result.

`ensure` and `ensureNotNull` are Arrow functions; import them from `arrow.core.raise`.

## Pipeline Methods

### `startWith`

Converts public input into the first workflow state:

```kotlin
startWith { command: RegisterUserCommand ->
  ensure(command.email.isNotBlank()) { RegistrationError.EmailRequired }
  RegistrationDraft(command.email, command.password)
}
```

The block runs with an Arrow `Raise<E>` receiver, where `E` is the use case's error type. Return the state directly, and fail with `ensure`, `ensureNotNull`, `raise`, or `bind()`. Exceptions thrown by the block become `Failure.Unexpected`.

If the block produces an `Either`, call `.bind()` on it. Returning the `Either` itself fails the use case with `Failure.Unexpected`, caused by a `WorkflowCompositionException`, instead of making the `Either` the state.

### `then`

Runs a step on the current state. Its output becomes the new state. Write the step inline, or pass a reusable one:

```kotlin
.then("hash-password") { draft -> draft.copy(password = hasher.hash(draft.password)) }
.then(validateRegistration)
.then(createUser(users))
```

A reusable step must be a `Workflow<S, O, E>` where `S` is the current state type and `E` is the pipeline's error type, a subtype of it, or `Nothing`. A mismatch is a compile error.

To run a step on part of the state and keep the rest, pass `input` to pick what it needs and a `merge` block to put its output back. The current state is the receiver of `merge`:

```kotlin
.then(reserveInventory, input = { checkout -> checkout.order }) { reservation -> copy(reservation = reservation) }
```

The step keeps its own id in execution history. Exceptions thrown by `input` or `merge` become `Failure.Unexpected` attributed to the step.

Pass a reusable pipeline to `then` to append its steps (see [Reusable Pipelines](#reusable-pipelines)).

### `guard`

Checks the current state and passes it on unchanged:

```kotlin
.guard("authorize") { order -> ensure(order.customer.canOrder) { OrderError.NotAllowed } }
```

### `thenIf`

Runs a workflow only when a predicate matches:

```kotlin
.thenIf(sendWelcomeEmail) { user -> user.marketingOptIn && context[FeatureFlags.WELCOME_EMAIL] == true }
```

The predicate receives the current state as its parameter and a `WorkflowResult<S>` as its receiver, so `state`, `events`, and `context` are available. Because the step may be skipped, it must be a `Workflow<S, S, E>`. Keep predicates small and side-effect free. Exceptions thrown by the predicate become `Failure.Unexpected`.

### `branch`

Runs one of two workflows, chosen by a predicate. Both produce the same next state type:

```kotlin
.branch(ifTrue = expressShipping, ifFalse = standardShipping) { order -> order.expedited }
```

The predicate works like the one for `thenIf`. Whichever workflow runs is recorded under its own id. Use `thenIf` when the alternative is to do nothing.

### Reusable Pipelines

`pipeline<S, E>()` starts a sequence of steps that takes state `S` and raises `E`, outside any use case. Build it with the usual pipeline methods and append it with `then`:

```kotlin
val fulfilOrder: Pipeline<PaidOrder, OrderError, Shipment> =
  pipeline<PaidOrder, OrderError>()
    .then(reserveInventory)
    .then(shipOrder)

useCase("PlaceOrder") {
  startWith { command -> OrderDraft(command) }
    .then(capturePayment)
    .then(fulfilOrder)
    .result { shipment -> shipment.toDto() }
}
```

Its steps run as if they were written in place: each is recorded under its own id, and its events join the use case's. Its error type may be a subtype of the use case's. Use it to share a sequence between use cases, or to give a long pipeline named sections.

### `parallel`

Runs side-effect workflows concurrently on the current state and keeps that state:

```kotlin
.parallel(sendReceiptEmail, publishPaymentEvent)
.parallel(sendReceiptEmail, publishPaymentEvent, policy = ParallelErrorPolicy.FailFast)
```

Each workflow must accept `S`; its output is ignored. Events are merged in parameter order. A failing branch fails the use case.

### `parallelJoin`

Runs state-producing workflows concurrently on the current state and merges their outputs into a new state:

```kotlin
.parallelJoin(calculateSubtotal, calculateTax) { subtotal, tax ->
  PricedOrder(order = this, subtotal = subtotal.amount, tax = tax.amount) // `this` is the current state
}
```

There are overloads for 2 through 8 workflows. The current state is the receiver (`this`) of the merge block, so the new state can keep fields of the old one.

`policy` is a named parameter that defaults to `ParallelErrorPolicy.WaitAll`, which waits for every branch and fails with the first error by parameter order. `ParallelErrorPolicy.FailFast` cancels the remaining branches on the first failure and fails with the first error by parameter order among the branches that finished.

Branches run with the same read-only context. Their events are merged in parameter order, and branches that succeeded are kept (their events count in the summary) even when another branch fails.

`parallelJoin` is also a top-level function that returns a `Workflow<I, R, E>`. Use it to name a join or reuse it:

```kotlin
val priceOrder: Workflow<ValidatedOrder, PricedOrder, OrderError> =
  parallelJoin(calculateSubtotal, calculateTax) { subtotal, tax ->
    PricedOrder(order = this, subtotal = subtotal.amount, tax = tax.amount) // `this` is the input
  }

startWith { command -> command.toValidatedOrder() }
  .then(priceOrder)
```

### `thenLaunch` And `awaitLaunched`

`thenLaunch` starts a side-effect workflow without waiting for it. The main chain continues with the same state:

```kotlin
.thenLaunch(sendAuditEvent)
.thenLaunch(sendWelcomeEmail, timeout = 2.seconds)
.then(continueMainFlow)
.awaitLaunched()
```

`timeout` is a `kotlin.time.Duration?`. It defaults to the config's `launchTimeout`, or to no timeout.

`awaitLaunched()` waits for everything launched so far and merges its events. Use it when later steps or the result need them. Work that is still running when the pipeline ends is awaited and merged before `result { }` runs, so `awaitLaunched()` at the very end is not required.

A launched failure or timeout never fails the use case. Each one is recorded as a `LaunchedFailureEvent` (with `workflowId` and a `message` free of user data: the raised error's class name, `An exception occurred`, or `thenLaunch timeout: <id>`) and in `UseCaseSummary.launchedFailures`. `context[WorkflowContext.LAUNCHED_FAILURES]` still holds the same messages but is deprecated and will be removed in 1.0.

Without a `timeout`, the config's `launchTimeout` applies, if one is set (see [Use Case Logging DSL](#use-case-logging-dsl)).

Launched work runs inside the use case invocation, so later steps don't wait for it but the use case does. `invoke` returns only after every launched step has finished, on the failure path too: when a later step fails, launched work is not cancelled, and the failed result waits for it. Without a `timeout` that wait is unbounded, so pass one for anything that talks to the network. Cancelling the caller cancels launched work too.

## Result Projection

`result { }` is the last call in the pipeline. It projects the final state into the public output:

```kotlin
.result { user -> UserDto(user.id, user.email) }
```

The block's receiver is a `ResultScope<S, E>`, which is an Arrow `Raise<E>` and also exposes:

| Member | Purpose |
| --- | --- |
| `events` | Every event emitted by the pipeline, in order. |
| `context` | The final context. |
| `result` | The final `WorkflowResult<S>` (state, events, context). |
| `requireEvent<T>()` | The last event of type `T`. When none was emitted the use case was assembled wrongly, so it fails with `Failure.Unexpected` caused by a `WorkflowCompositionException`. |

Combine them as needed:

```kotlin
.result { order ->
  OrderReceipt(order.id, order.total, events.lastEvent<ShipmentPrepared>()?.trackingNumber)
}

.result { requireEvent<UserRegisteredEvent>() }
```

Exceptions thrown by the block become `Failure.Unexpected`.

## Writing Workflows

Steps are functions; you never implement `Workflow` yourself. Write steps inline on the pipeline:

```kotlin
.then("reserve-inventory") { order -> inventory.reserve(order) }
.guard("authorize") { order -> ensure(order.customer.canOrder) { OrderError.NotAllowed } }
```

Or define a reusable step with the `workflow` function:

```kotlin
val validateOrder = workflow<OrderDraft, ValidatedOrder, OrderError>("validate-order") { draft ->
  ensure(draft.items.isNotEmpty()) { OrderError.EmptyOrder }
  emit(OrderValidated(draft.orderId))
  ValidatedOrder(draft.orderId, draft.items)
}
```

`id` is a stable identifier used in execution history, logs, and summaries. The third type argument is the error type the step can raise; use `Nothing` for a step that never raises. Inline steps take the pipeline's error type, so they need no type arguments. `parallel`, `parallelJoin`, `thenIf` and `thenLaunch` take reusable steps.

Business logic belongs in ordinary functions that don't depend on this library. A function that can fail declares an Arrow `Raise` context parameter, and any step block with that error type can call it, because the block is a `Raise<OrderError>`:

```kotlin
class Inventory(private val stock: StockClient) {
  context(_: Raise<OrderError>)
  suspend fun reserve(order: ValidatedOrder): ReservedOrder {
    val reservation = stock.reserve(order.items) ?: raise(OrderError.OutOfStock)
    return ReservedOrder(order, reservation)
  }
}
```

Inside `context(_: Raise<...>)` functions, import `ensure` and `ensureNotNull` from `arrow.core.raise.context`. Kotlin doesn't allow method references to functions with context parameters, so call them from a lambda: `{ inventory.reserve(it) }`, not `inventory::reserve`.

Use `guard` for a reusable step that only checks its input and passes it on unchanged:

```kotlin
val authorize = guard<PlaceOrder, OrderError>("authorize") { command ->
  ensure(command.customer.canOrder) { OrderError.NotAllowed }
}
```

`WorkflowStepScope<E>` is an Arrow `Raise<E>`. It also provides:

| Member | Purpose |
| --- | --- |
| `context` | The request metadata the use case was invoked with. Read-only. |
| `emit(event)` | Records a domain event. Safe to call from coroutines launched inside the step. |

Data a step produces for later steps belongs in the state it returns, not in the context. Exceptions thrown by a step become `Failure.Unexpected`.

Adapt a reusable step to a different input or output type with `mapInput` and `mapOutput`, the same way `mapError` adapts its error type. The adapted step keeps its id:

```kotlin
.thenLaunch(sendReceipt.mapInput { order: PaidOrder -> order.customer })
```

### Running A Step On Its Own

Call a workflow directly to unit-test it:

```kotlin
val result: Either<Failure<OrderError>, WorkflowResult<ValidatedOrder>> =
  validateOrder(OrderDraft(orderId, items), WorkflowContext())
```

The context argument is optional. The `WorkflowResult` holds the new `state`, the `events` the step emitted, and the `context` it ran with.

## Error Handling

Error types are your own; they need no library supertype. A sealed hierarchy works best:

```kotlin
sealed interface OrderError {
  data object EmptyOrder : OrderError
  data class PaymentDeclined(val reason: String) : OrderError
}
```

Every step of a pipeline raises the pipeline's error type `E`, a subtype of it, or `Nothing`. A step whose error type doesn't fit is a compile error. Adapt a step written for another error type with `mapError`, which keeps its `id`:

```kotlin
.then(chargeCard.mapError { e: PaymentError -> OrderError.PaymentDeclined(e.reason) })
```

A failed use case or step reports a `Failure<E>`:

| Case | Meaning |
| --- | --- |
| `Failure.Raised(error)` | A step, `startWith`, or `result { }` raised `error`. |
| `Failure.Unexpected(cause)` | Something threw: a step, `startWith`, a predicate, or `result { }`. This includes a step's own timeout (use `withTimeoutOrNull(...) ?: raise(...)` to raise a typed error instead) and a `thenLaunch` timeout. A use case assembled wrongly, such as a missing `requireEvent` or a `startWith` that returned an `Either`, fails with a `WorkflowCompositionException` as the cause, so logs and summaries name it. |

The `errors` mapper receives the `Failure`, so its `when` is exhaustive with no `else`. Adding a case to `OrderError` is a compile error until the mapper handles it:

```kotlin
private fun toApiError(failure: Failure<OrderError>): ApiError =
  when (failure) {
    is Failure.Raised -> when (val error = failure.error) {
      OrderError.EmptyOrder -> ApiError(400, "empty-order")
      is OrderError.PaymentDeclined -> ApiError(402, error.reason)
    }
    is Failure.Unexpected -> ApiError(500, "internal")
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

Steps, `thenIf` and `branch` predicates, and `result { }` read `context[key]`. Steps can't change the context.

Built-in keys:

| Key | Value |
| --- | --- |
| `WorkflowContext.CORRELATION_ID` | `Key<String>`. Reported in `UseCaseSummary.correlationId`, and put in the SLF4J MDC as `correlationId` while the use case runs. |
| `WorkflowContext.LAUNCHED_FAILURES` | Deprecated. The library still adds launched failure messages here; read `UseCaseSummary.launchedFailures` or `LaunchedFailureEvent`s instead. Removed in 1.0. |

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

When the context has a `CORRELATION_ID`, the use case puts it in the SLF4J MDC under `correlationId` for the whole invocation, including its own log lines and any logging inside steps, and restores the previous MDC afterwards. Add `%X{correlationId}` to your log pattern to see it.

## Use Case Logging DSL

Build one `UseCaseConfig` at application startup and pass it to each use case, the same way you pass other dependencies. Use cases built without one use `UseCaseConfig.Default`:

```kotlin
val appConfig = useCaseConfig {
  outcomeStyle(OutcomeStyle.Plain)
  defaultSummaryFields(DefaultSummaryFields.Enabled)
  onSummary { summary -> metrics.record(summary) }
}

fun placeOrder(payments: Payments, config: UseCaseConfig) =
  useCase("PlaceOrder", errors = ::toApiError, config = config) { ... }
```

To adjust the application config for one use case, pass it as the base: `useCaseConfig(appConfig) { outcomeStyle(OutcomeStyle.AnsiColor) }`. There is no global state, so tests can give each use case its own listener.

Options:

| Setting | Values | Default |
| --- | --- | --- |
| `outcomeStyle` | `OutcomeStyle.Plain`, `OutcomeStyle.AnsiColor` | `OutcomeStyle.Plain` |
| `defaultSummaryFields` | `DefaultSummaryFields.Enabled`, `DefaultSummaryFields.Disabled` | `DefaultSummaryFields.Enabled` |
| `onSummary` | `(UseCaseSummary) -> Unit` | none |
| `launchTimeout` | A positive `Duration`, used by `thenLaunch` calls that pass no `timeout` | none |

`onSummary` adds a listener that is called with a `UseCaseSummary` after every use case invocation, whatever the log level. Each call adds another listener. Listeners run on the caller's coroutine; exceptions they throw are logged and ignored.

Log fields render as stable `key=value` pairs.

Per-use-case logging fields are explicit. Chain `.logging { }` after `result { }`:

```kotlin
.result { user -> user.toDto() }
.logging {
  success { result -> field("userId", result.state.id) }
  success { (_, _, context) -> field("tenant", context[Keys.TENANT]) }
  failure { failure -> field("unexpected", failure is Failure.Unexpected) }
}
```

- `success` receives the final `WorkflowResult<S>` (state, events, context) and can be added more than once.
- `failure` receives the `Failure<E>` of the use case's error type, before the `errors` mapper runs, and can be set once.

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
| `launchedFailures` | One message per `LaunchedFailureEvent`: `thenLaunch failure <workflowId> (<message>)`. |
| `correlationId` | Value of `WorkflowContext.CORRELATION_ID`, if set. |

`RootErrorInfo` has the `type` (class name of the raised error, or of the exception), whether the failure was `unexpected`, the `message` (the raised error's `toString()` or the exception's message), and the `workflowId` of the failing step when the failure came from a step. Launched side effects never count as the failing step.

Error messages are redacted by default because they may contain user data, so `rootError.message` is `null`. Use `withErrorMessages()` to get a copy that includes it:

```kotlin
useCaseConfig {
  onSummary { summary -> log.info(summary.withErrorMessages().toString()) }
}
```

Only use `withErrorMessages()` when those messages are known to be safe for your environment.
