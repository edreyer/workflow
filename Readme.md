# Workflow

`workflow` is a Kotlin DSL for making application use cases explicit.

Use it when a business operation has multiple steps, conditional branches, parallel work, domain events, or boundary-specific input/output needs. Instead of scattering that flow across services and helper methods, define the use case as one typed pipeline:

```text
public input -> startWith -> state -> step -> state -> step -> ... -> result -> public output
```

## Why Use It

- Make use cases visible in one place.
- Keep workflow steps small, isolated, and testable.
- Pass typed state between steps. The compiler checks that each step accepts the previous step's output.
- Compose sequential, conditional, parallel, and launched side-effect work.
- Fail with typed errors, including your own, using Arrow's `Raise` (`ensure`, `raise`, `bind()`), and get an `Either` back.
- Get debug invocation logs and summaries that never include command, state, event, output, or error payloads unless you ask for them.

For the architectural motivation, see [Make the Use Cases of Your App Explicit](docs/make-use-cases-explicit.md).

## Quick Example

```kotlin
import arrow.core.Either
import arrow.core.raise.ensure
import io.liquidsoftware.workflow.BaseEvent
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.WorkflowError
import io.liquidsoftware.workflow.useCase
import io.liquidsoftware.workflow.workflow
import java.util.UUID

data class RegisterUser(val email: String, val password: String)
data class RegistrationDraft(val email: String, val password: String)
data class RegisteredUser(val id: UUID, val email: String)
data class UserDto(val id: UUID, val email: String)
data class UserRegistered(val userId: UUID) : BaseEvent()

sealed interface RegistrationError : WorkflowError {
  data object EmailTaken : RegistrationError
}

data class ApiError(val status: Int, val code: String)

fun createUser(users: UserRepository) =
  workflow<RegistrationDraft, RegisteredUser>("create-user") { draft ->
    ensure(!users.exists(draft.email)) { RegistrationError.EmailTaken }
    val user = users.create(draft.email, draft.password)
    emit(UserRegistered(user.id))
    RegisteredUser(user.id, user.email)
  }

fun registerUser(users: UserRepository): UseCase<RegisterUser, ApiError, UserDto> =
  useCase(
    name = "RegisterUser",
    errors = { error ->
      when (error) {
        RegistrationError.EmailTaken -> ApiError(409, "email-taken")
        is WorkflowError.ValidationError -> ApiError(400, "invalid")
        else -> ApiError(500, "internal")
      }
    },
  ) {
    startWith { command ->
      ensure(command.email.isNotBlank()) { WorkflowError.ValidationError("Email is required") }
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
4. `errors` maps every failure, including your own error types, to the error type your callers expect.

## Defining Workflow Steps

A step turns one state into the next. It runs inside a `WorkflowStepScope`, which is an Arrow `Raise<WorkflowError>`, so it returns its new state and fails with `ensure`, `ensureNotNull`, `raise`, or `bind()`. It can also `emit` domain events and `put` typed context data for later steps.

The lightest form is the `workflow` function:

```kotlin
val validateOrder = workflow<OrderDraft, ValidatedOrder>("validate-order") { draft ->
  ensure(draft.items.isNotEmpty()) { OrderError.EmptyOrder }
  emit(OrderValidated(draft.orderId))
  put(Keys.PRIORITY, draft.isPriority)
  ValidatedOrder(draft.orderId, draft.items)
}
```

Use `guard` for a step that only checks its input, such as authorization, and passes it on unchanged:

```kotlin
val authorize = guard<PlaceOrder>("authorize") { command ->
  ensure(command.customer.canOrder) { OrderError.NotAllowed }
}
```

Implement `Workflow<I, O>` when a step needs its own class, for example to inject dependencies:

```kotlin
class CapturePayment(private val gateway: PaymentGateway) : Workflow<PricedOrder, PaidOrder> {
  override val id = "capture-payment"

  override suspend fun WorkflowStepScope.execute(input: PricedOrder): PaidOrder {
    val receipt = gateway.capture(input.total).bind()
    emit(PaymentCaptured(input.orderId, receipt.id))
    return PaidOrder(input, receipt)
  }
}
```

`ensure`, `ensureNotNull` and the other `Raise` helpers are imported from `arrow.core.raise`. Thrown exceptions, including a timeout a step starts with its own `withTimeout`, become `WorkflowError.ExceptionError`. To unit-test a step, call it directly: `validateOrder(draft)` returns `Either<WorkflowError, WorkflowResult<ValidatedOrder>>` with the new state, the events, and the context.

## Composing Steps

```kotlin
val processOrder: UseCase<ProcessOrder, ApiError, OrderReceipt> =
  useCase(name = "ProcessOrder", errors = ::toApiError) {
    startWith { command -> OrderDraft(command.orderId, command.items, command.paymentToken) }
      .then(validateOrder)
      .parallelJoin(calculateSubtotal, calculateTax) { subtotal, tax ->
        PricedOrder(order = this, subtotal = subtotal.amount, tax = tax.amount)
      }
      .parallel(CheckInventory(), CapturePayment())
      .thenIf(prepareShipment) { context[Keys.INVENTORY_AVAILABLE] == true }
      .thenLaunch(sendConfirmation)
      .result { order -> OrderReceipt(order.order.orderId, order.total) }
  }
```

| Pipeline method | Use when |
| --- | --- |
| `startWith { input -> state }` | Convert public input into the first state. |
| `.then(step)` | Run the next step. Its output becomes the new state. |
| `.thenIf(step) { state -> Boolean }` | Run a step only when the predicate matches. The step must keep the state type. |
| `.parallel(a, b, ...)` | Run side-effect steps concurrently and keep the current state. |
| `.parallelJoin(a, b, ...) { x, y -> state }` | Run 2 to 8 steps concurrently and merge their outputs into a new state. The current state is `this`. |
| `.parallelJoin(..., policy = ParallelErrorPolicy.FailFast) { ... }` | Cancel the remaining branches on the first failure. The default, `WaitAll`, waits for all branches and returns the first error by parameter order. |
| `.thenLaunch(step, timeout = 2.seconds)` | Start side-effect work without waiting for it. Its failures never fail the use case. |
| `.awaitLaunched()` | Wait for launched work when a later step needs its events or context. Pending work is always merged before `.result`. |
| `.result { state -> output }` | Project public output. `events`, `context`, and `requireEvent<T>()` are in scope. Required. |
| `.logging { success { }; failure { } }` | Add explicit fields to the debug finish log. |

Kotlin needs to know the input type to type `startWith`. Give the use case an explicit type, as above, or pass the type arguments: `useCase<ProcessOrder, ApiError, OrderReceipt>(...) { ... }`.

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

Every failure is a `WorkflowError`. The library provides `ValidationError`, `ExecutionError`, `DomainError`, `ExceptionError` (for anything thrown), and `CompositionError` (for a missing required event). Applications can implement `WorkflowError` with their own sealed types and raise them from any step:

```kotlin
sealed interface OrderError : WorkflowError {
  data object EmptyOrder : OrderError
  data class PaymentDeclined(val reason: String) : OrderError
}
```

Errors reach the `errors` mapper exactly as they were raised. Omit `errors` to get `UseCase<I, WorkflowError, O>`. The mapper should be total and non-throwing. If it throws, the exception propagates to the caller.

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

Steps read `context[Keys.TENANT]` and add values with `put(key, value)`. Keep context values operational. Don't use the context to pass domain data around the typed state.

## Observability And Data Safety

Use cases emit debug-level SLF4J logs when they start and finish. By default these logs include safe operational metadata only:

- use case name
- success or failure outcome
- duration
- emitted event count
- executed workflow count

They do not log command input, workflow state, event payloads, projected output, or error payloads unless you explicitly add fields. Keep `name` stable and free of user data.

Configure formatting and summary listeners once at application startup:

```kotlin
useCaseLoggingGlobalConfig {
  outcomeStyle(OutcomeStyle.Plain)
  defaultSummaryFields(DefaultSummaryFields.Enabled)
  onSummary { summary -> metrics.record(summary) }
}
```

`onSummary` receives a `UseCaseSummary` after every invocation: name, outcome, duration, event count, executed steps, the failing step and error code, launched failures, and correlation ID. Error messages are redacted unless you call `summary.withErrorMessages()`.

Add per-use-case log fields after `.result`. They are typed to the final result and the mapped error, and are only evaluated when debug logging is enabled:

```kotlin
.result { user -> UserDto(user.id, user.email) }
.logging {
  success { (user, events) -> field("userId", user.id) }
  failure { error -> field("status", error.status) }
}
```

A logging block that throws, or that uses one of the reserved names `name`, `outcome`, `durationMs`, `events`, or `workflows`, is reported separately and never changes the use case result.

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
- [Migrating to v0.9.0 Typed Pipelines](docs/migrations/0.9.0-typed-pipelines.md) shows every breaking change with before and after examples.
- [Migrating to v0.8.0 Use Case Logging](docs/migrations/0.8.0-use-case-logging.md) explains how to adopt the logging DSL.
- [Migrating from v0.6.0 to v0.7.0](docs/migrations/0.7.0-use-cases.md) explains the breaking API changes.
