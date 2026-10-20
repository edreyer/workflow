# Workflow

`workflow` is a Kotlin DSL for making application use cases explicit.

It orchestrates a single request in process. It is not a durable workflow engine like Temporal, Conductor, or Camunda: nothing is persisted, and a use case does not survive a restart or resume after a crash.

In most applications, use cases are implicit. "Place an order" is a controller calling an `OrderService`, which calls `PaymentService` and `InventoryService`, each adding its own conditionals and side effects. To learn what the operation does, you trace call chains across the codebase, and services grow coupled to each other because each one orchestrates a piece of the flow.

`workflow` formalizes the use case as a concept in your code. A `UseCase<Input, Error, Output>` is a type of its own, and each one lists its steps in order, with typed state between them. Services go back to being capabilities (store data, charge a card, send an email) that steps call, and they stop calling each other. Your application's use cases become a set of named values you can find, read, and test.

Use it when a business operation has multiple steps, conditional branches, parallel work, domain events, or boundary-specific input/output needs. Define the use case as one typed pipeline:

```text
public input -> startWith -> state -> step -> state -> step -> ... -> result -> public output
```

## Why Use It

- Make use cases visible in one place.
- Keep workflow steps small, isolated, and testable.
- Pass typed state between steps. The compiler checks that each step accepts the previous step's output.
- Compose sequential, conditional, parallel, and launched side-effect work.
- Fail with your own sealed error types, using Arrow's `Raise` (`ensure`, `raise`, `bind()`), and map them with an exhaustive `when`.
- Get debug invocation logs and summaries that never include command, state, event, output, or error payloads unless you ask for them.

A single use case can also be written as a plain function with Arrow's `either { }`. What that lacks is the concept: nothing marks the function as a use case, and nothing gives every use case the same shape. The library stays lightweight and adds exactly that, plus named, timed steps that are attributed when they fail.

For the architectural motivation, see [Make the Use Cases of Your App Explicit](docs/make-use-cases-explicit.md).

## Quick Example

```kotlin
import arrow.core.Either
import arrow.core.raise.ensure
import io.liquidsoftware.workflow.BaseEvent
import io.liquidsoftware.workflow.Failure
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.useCase
import io.liquidsoftware.workflow.workflow
import java.util.UUID

data class RegisterUser(val email: String, val password: String)
data class RegistrationDraft(val email: String, val password: String)
data class RegisteredUser(val id: UUID, val email: String)
data class UserDto(val id: UUID, val email: String)
data class UserRegistered(val userId: UUID) : BaseEvent()

sealed interface RegistrationError {
  data object EmailRequired : RegistrationError
  data object EmailTaken : RegistrationError
}

data class ApiError(val status: Int, val code: String)

fun createUser(users: UserRepository) =
  workflow<RegistrationDraft, RegisteredUser, RegistrationError>("create-user") { draft ->
    ensure(!users.exists(draft.email)) { RegistrationError.EmailTaken }
    val user = users.create(draft.email, draft.password)
    emit(UserRegistered(user.id))
    RegisteredUser(user.id, user.email)
  }

fun registerUser(users: UserRepository): UseCase<RegisterUser, ApiError, UserDto> =
  useCase(
    name = "RegisterUser",
    errors = { failure: Failure<RegistrationError> ->
      when (failure) {
        is Failure.Raised -> when (failure.error) {
          RegistrationError.EmailRequired -> ApiError(400, "invalid")
          RegistrationError.EmailTaken -> ApiError(409, "email-taken")
        }
        is Failure.Unexpected -> ApiError(500, "internal")
      }
    },
  ) {
    startWith { command ->
      ensure(command.email.isNotBlank()) { RegistrationError.EmailRequired }
      RegistrationDraft(command.email, command.password)
    }
      .then(createUser(users))
      .result { user -> UserDto(user.id, user.email) }
  }

suspend fun handle(users: UserRepository): Either<ApiError, UserDto> =
  registerUser(users)(RegisterUser("person@example.com", "password"))
```

The important part is the shape:

1. `startWith` converts public input into the first state.
2. `.then(...)` runs a focused workflow step. It only compiles if the step accepts the current state.
3. `.result { }` projects the typed final state into public output.
4. `errors` maps every failure to the error type your callers expect. Because `RegistrationError` is sealed, the `when` has no `else`, and adding an error case is a compile error until it is mapped.

## Defining Workflow Steps

Steps are functions. Write your business logic as ordinary Kotlin, and let the pipeline name each step where it wires it in:

```kotlin
class Payments(private val gateway: PaymentGateway) {
  context(_: Raise<OrderError>)
  suspend fun capture(order: PricedOrder): PaidOrder {
    val receipt = gateway.capture(order.total) ?: raise(OrderError.PaymentDeclined("card declined"))
    return PaidOrder(order, receipt)
  }
}

startWith { command -> OrderDraft(command.orderId, command.items) }
  .then("validate-order") { draft -> validator.validate(draft) }
  .guard("authorize") { order -> ensure(order.customer.canOrder) { OrderError.NotAllowed } }
  .then("capture-payment") { order ->
    payments.capture(order).also { emit(PaymentCaptured(order.orderId, it.receipt.id)) }
  }
```

`Payments` doesn't depend on this library. A function that can fail declares `context(_: Raise<OrderError>)`, which is plain Arrow, and you can test it with `either { payments.capture(order) }`. Inside a step block you can also `emit` domain events and read the request `context`. `.guard` checks the state and passes it on unchanged.

To name a step once and reuse it, or to pass it to `parallel`, `parallelJoin`, `thenIf` or `thenLaunch`, define it with `workflow`:

```kotlin
val validateOrder = workflow<OrderDraft, ValidatedOrder, OrderError>("validate-order") { draft ->
  ensure(draft.items.isNotEmpty()) { OrderError.EmptyOrder }
  emit(OrderValidated(draft.orderId))
  ValidatedOrder(draft.orderId, draft.items)
}
```

The third type argument is the error type the step can raise. Use `Nothing` for a step that never raises; it fits any pipeline. `guard<T, OrderError>("id") { }` builds a reusable pass-through check the same way. `mapInput`, `mapOutput`, and `mapError` adapt a reusable step to a pipeline whose types differ, and keep its id.

Import `ensure` and the other `Raise` helpers from `arrow.core.raise`, or from `arrow.core.raise.context` inside `context(_: Raise<...>)` functions. Thrown exceptions, including a timeout a step starts with its own `withTimeout`, become `Failure.Unexpected`. To unit-test a reusable step, call it directly: `validateOrder(draft)` returns `Either<Failure<OrderError>, WorkflowResult<ValidatedOrder>>` with the new state, the events, and the context.

A reusable step can be given a timeout, retries, and a fallback. Each returns a step with the same id. Retry policy is an Arrow [`Schedule`](https://arrow-kt.io/learn/resilience/retry-and-repeat/), which receives each `Failure`:

```kotlin
val transient = Schedule.exponential<Failure<PaymentError>>(200.milliseconds)
  .and(Schedule.recurs(2))
  .doWhile { failure, _ -> failure is Failure.Unexpected || failure == Failure.Raised(PaymentError.TimedOut) }

val chargeCard = workflow<PricedOrder, PaidOrder, PaymentError>("charge-card") { payments.capture(it) }
  .timeout(2.seconds) { PaymentError.TimedOut } // a raised error, not Failure.Unexpected
  .retry(transient)                             // keeps only the successful attempt's events
  .recover { order, error ->                    // raised errors only; exceptions pass through
    if (error is PaymentError.Declined) queueForManualReview(order) else raise(error)
  }
```

## Composing Steps

```kotlin
val processOrder: UseCase<ProcessOrder, ApiError, OrderReceipt> =
  useCase(name = "ProcessOrder", errors = ::toApiError) {
    startWith { command -> OrderDraft(command.orderId, command.items, command.paymentToken) }
      .then(validateOrder)
      .parallelJoin(calculateSubtotal, calculateTax) { subtotal, tax ->
        PricedOrder(order = this, subtotal = subtotal.amount, tax = tax.amount)
      }
      .then(checkInventory)
      .parallel(capturePayment, notifyWarehouse)
      .thenIf(prepareShipment) { order -> order.inventoryAvailable }
      .thenLaunch(sendConfirmation)
      .result { order -> OrderReceipt(order.order.orderId, order.total) }
  }
```

| Pipeline method | Use when |
| --- | --- |
| `startWith { input -> state }` | Convert public input into the first state. |
| `.then("id") { state -> next }` | Run the next step, written inline. Its output becomes the new state. |
| `.then(step)` | Run a reusable step defined with `workflow`. |
| `.then(step, input = { it.order }) { out -> copy(order = out) }` | Run a step on part of the state and merge its output back. The current state is `this`. |
| `.then(fulfilOrder)` | Append a reusable pipeline built with `pipeline<S, E>()`. Its steps keep their own ids. |
| `.guard("id") { state -> }` | Check the state with `ensure` or `raise` and pass it on unchanged. |
| `.thenIf(step) { state -> Boolean }` | Run a step only when the predicate matches. The step must keep the state type. |
| `.branch(ifTrue, ifFalse) { state -> Boolean }` | Run one of two steps that produce the same next state type. |
| `.parallel(a, b, ...)` | Run side-effect steps concurrently and keep the current state. |
| `.parallelJoin(a, b, ...) { x, y -> state }` | Run 2 to 8 steps concurrently and merge their outputs into a new state. The current state is `this`. |
| `.parallelJoin(..., policy = ParallelErrorPolicy.FailFast) { ... }` | Cancel the remaining branches on the first failure. The default, `WaitAll`, waits for all branches and returns the first error by parameter order. |
| `.thenLaunch(step, timeout = 2.seconds)` | Start side-effect work that later steps don't wait for. The use case still returns only after launched work finishes, even when a step fails, so pass `timeout`, or set `launchTimeout` in the config, for anything that talks to the network. Its failures never fail the use case. |
| `.awaitLaunched()` | Wait for launched work when a later step needs its events or failures. Pending work is always merged before `.result`. |
| `.result { state -> output }` | Project public output. `events`, `context`, and `requireEvent<T>()` are in scope. Required. |
| `.logging { success { }; failure { } }` | Add explicit fields to the debug finish log. |

Kotlin needs to know the input type to type `startWith`. Give the use case an explicit type, as above, or pass the type arguments: `useCase<ProcessOrder, OrderError, ApiError, OrderReceipt>(...) { ... }`.

For deeper API notes, see the [Workflow Reference](docs/reference.md).

### Supplying Dependencies

A use case is a value, so give it dependencies the way you would any other object. The library has no dependency injection of its own and works with Spring, Koin or plain constructors.

The lightest option is a function that takes the services and returns the use case:

```kotlin
fun placeOrder(payments: Payments): UseCase<PlaceOrder, ApiError, OrderDto> =
  useCase(name = "PlaceOrder", errors = ::toApiError) {
    startWith { command -> OrderDraft(command.id, command.items) }
      .then(validateOrder)
      .then(capturePayment(payments))
      .result { order -> order.toDto() }
  }
```

When a dependency injection framework needs one type per use case, delegate to the built use case:

```kotlin
class PlaceOrderUseCase(payments: Payments) :
  UseCase<PlaceOrder, ApiError, OrderDto> by placeOrder(payments)
```

Callers depend on `PlaceOrderUseCase` or `UseCase<PlaceOrder, ApiError, OrderDto>`. `UseCase` is a `fun interface`, so a test can stand in a lambda: `UseCase { _, _ -> Either.Right(dto) }`. A class that groups several use cases is fine when they share dependencies, but it is never required.

## Errors

Error types are your own. Steps raise them, usually from a sealed hierarchy with no library supertype:

```kotlin
sealed interface OrderError {
  data object EmptyOrder : OrderError
  data class PaymentDeclined(val reason: String) : OrderError
}
```

Every step in a pipeline raises the pipeline's error type, a subtype of it, or `Nothing`. A step written for an unrelated error type is a compile error, like a step that doesn't accept the current state. Adapt it with `mapError`:

```kotlin
.then(chargeCard.mapError { e: PaymentError -> OrderError.PaymentDeclined(e.reason) })
```

The `errors` mapper receives a `Failure<OrderError>`: either `Failure.Raised(error)`, or `Failure.Unexpected(cause)` for an exception nobody raised on purpose. A use case wired wrongly, such as a `requireEvent` for an event no step emits, fails with a `WorkflowCompositionException` as the cause. The mapper's parameter type tells the use case its error type:

```kotlin
fun toApiError(failure: Failure<OrderError>): ApiError = when (failure) {
  is Failure.Raised -> when (val error = failure.error) {
    OrderError.EmptyOrder -> ApiError(400, "empty-order")
    is OrderError.PaymentDeclined -> ApiError(402, error.reason)
  }
  is Failure.Unexpected -> ApiError(500, "internal")
}
```

Omit `errors` to get `UseCase<I, Failure<OrderError>, O>`. The mapper should be total and non-throwing. If it throws, the exception propagates to the caller.

The pipeline stops at the first failure.

## Context

`WorkflowContext` carries typed request metadata, such as a correlation ID, through the use case. Declare each `Key` once and share it, because keys compare by identity:

```kotlin
object Keys {
  val TENANT = Key<String>("tenant")
}

val result = processOrder(
  command,
  WorkflowContext.of(WorkflowContext.CORRELATION_ID to "corr-123", Keys.TENANT to "acme"),
)
```

Steps, `thenIf` predicates and `result { }` read it with `context[Keys.TENANT]`. Steps can't change it: data a step produces goes in the state it returns, where the compiler can check it.

To keep domain functions free of `Key` lookups, have them declare what they need as a context parameter and supply it from the step:

```kotlin
data class Tenant(val id: String)

context(tenant: Tenant)
fun priceFor(order: Order): Int = if (tenant.id == "acme") order.total * 9 / 10 else order.total

.then("price") { order -> context(Tenant(context[Keys.TENANT] ?: "default")) { priceFor(order) } }
```

## Observability And Data Safety

Use cases emit debug-level SLF4J logs when they start and finish. By default these logs include safe operational metadata only:

- use case name
- success or failure outcome
- duration
- emitted event count
- executed workflow count

They do not log command input, workflow state, event payloads, projected output, or error payloads unless you explicitly add fields. Keep `name` stable and free of user data.

Configure formatting and summary listeners once at application startup, and pass the config to each use case like any other dependency:

```kotlin
val appConfig = useCaseConfig {
  outcomeStyle(OutcomeStyle.Plain)
  defaultSummaryFields(DefaultSummaryFields.Enabled)
  onSummary { summary -> metrics.record(summary) }
}

val processOrder = useCase("ProcessOrder", errors = ::toApiError, config = appConfig) { ... }
```

`onSummary` receives a `UseCaseSummary` after every invocation: name, outcome, duration, event count, executed steps, the failing step and error type, launched failures, and correlation ID. Error messages are redacted unless you call `summary.withErrorMessages()`.

When the context has a `WorkflowContext.CORRELATION_ID`, the use case puts it in the SLF4J MDC as `correlationId` while it runs, so your own log lines inside steps carry it too.

Add per-use-case log fields after `.result`. They are typed to the final result and to the `Failure` of the use case's error type, and are only evaluated when debug logging is enabled:

```kotlin
.result { user -> UserDto(user.id, user.email) }
.logging {
  success { (user, events) -> field("userId", user.id) }
  failure { failure -> field("unexpected", failure is Failure.Unexpected) }
}
```

A logging block that throws, or that uses one of the reserved names `name`, `outcome`, `durationMs`, `events`, or `workflows`, is reported separately and never changes the use case result.

## Testing

Test steps and use cases as plain suspend functions, with `runTest` from `kotlinx-coroutines-test`:

- Call a reusable step directly. `validateOrder(draft)` returns its new state, events, and context, or the `Failure`.
- Invoke a use case with fake services and assert on the mapped result. Register an `onSummary` listener in its `UseCaseConfig` to check which steps ran.
- Callers of a use case can stand in a lambda, because `UseCase` is a `fun interface`.

[Design Guidance](docs/design-guidance.md#testing) lists the edge cases worth covering.

## Installation

```kotlin
dependencies {
  implementation("io.liquidsoftware:workflow:<version>")
}
```

Requires Java 17 or later. Depends on Arrow `arrow-core`, `kotlinx-coroutines-core`, and `slf4j-api`.

## More Documentation

- [Make the Use Cases of Your App Explicit](docs/make-use-cases-explicit.md) explains the design problem this library addresses.
- [Design Guidance](docs/design-guidance.md) covers when to use this library and how to structure workflows.
- [Workflow Reference](docs/reference.md) covers every DSL method, errors, context, and logging.
- [Migrating to v0.10.0](docs/migrations/0.10.0.md) shows every breaking change with before and after examples.
- [Migrating to v0.9.0 Typed Pipelines](docs/migrations/0.9.0-typed-pipelines.md) shows every breaking change with before and after examples.
- [Migrating to v0.8.0 Use Case Logging](docs/migrations/0.8.0-use-case-logging.md) explains how to adopt the logging DSL.
- [Migrating from v0.6.0 to v0.7.0](docs/migrations/0.7.0-use-cases.md) explains the breaking API changes.
