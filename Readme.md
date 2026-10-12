# Workflow

`workflow` is a Kotlin DSL for making application use cases explicit.

Use it when a business operation has multiple steps, conditional branches, parallel work, domain events, or boundary-specific input/output needs. Instead of scattering that flow across services and helper methods, define the use case as one typed workflow:

```text
public input -> startWith -> workflow state -> workflow steps -> result -> public output
```

## Why Use It

- Make use cases visible in one place.
- Keep workflow steps small, isolated, and testable.
- Pass typed state between steps instead of relying on hidden service call order.
- Compose sequential, conditional, parallel, and launched side-effect work.
- Project public output from final state, emitted events, or a custom projection.
- Return typed errors with `Either`.
- Get configurable debug invocation logs without logging command, state, event, output, or error payloads by default.

For the architectural motivation, see [Make the Use Cases of Your App Explicit](docs/make-use-cases-explicit.md).

## Quick Example

```kotlin
import arrow.core.Either
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.UseCaseCommand
import io.liquidsoftware.workflow.WorkflowError
import io.liquidsoftware.workflow.WorkflowState
import io.liquidsoftware.workflow.state
import io.liquidsoftware.workflow.useCase
import io.liquidsoftware.workflow.workflow
import java.util.UUID

data class RegisterUserCommand(
  val email: String,
  val password: String,
) : UseCaseCommand

data class RegistrationDraft(
  val email: String,
  val password: String,
) : WorkflowState

data class RegisteredUser(
  val id: UUID,
  val email: String,
) : WorkflowState

data class UserDto(
  val id: UUID,
  val email: String,
)

data class ApplicationError(val code: String)

fun createUser(users: UserRepository) =
  workflow<RegistrationDraft, RegisteredUser>("create-user") { draft ->
    val user = users.create(draft.email, draft.password)
    RegisteredUser(user.id, user.email)
  }

val registerUser: UseCase<RegisterUserCommand, ApplicationError, UserDto> =
  useCase(
    name = "RegisterUser",
    errors = { error -> error.toApplicationError() },
  ) {
    startWith { command ->
      if (command.email.isBlank()) {
        Either.Left(WorkflowError.ValidationError("Email is required"))
      } else {
        Either.Right(RegistrationDraft(command.email, command.password))
      }
    }

    then(createUser(users))

    result(state<RegisteredUser>()) { state ->
      UserDto(id = state.id, email = state.email)
    }
  }

val result: Either<ApplicationError, UserDto> =
  registerUser(RegisterUserCommand("person@example.com", "password"))
```

The important part is the shape:

1. `startWith` converts public input into the initial workflow state.
2. `then` runs a focused workflow step.
3. `result(state<T>())` projects public output from the final state.
4. `errors` maps workflow-native errors to an application error type.

## The Public API

Most application code should use `useCase`.

```kotlin
val useCase: UseCase<Input, Error, Output> =
  useCase(name = "UseCaseName", errors = ::toError) {
    startWith { input -> Either.Right(InitialState(input.id)) }
    then(SomeWorkflow())
    result(state<FinalState>()) { state -> state.toOutput() }
  }

val output: Either<Error, Output> = useCase(input)
```

`UseCase<I, E, O>` is callable:

```kotlin
fun interface UseCase<I, E, O> {
  suspend operator fun invoke(input: I): Either<E, O>
  suspend operator fun invoke(input: I, context: WorkflowContext): Either<E, O>
}
```

Pass `WorkflowContext` when the request boundary has operational metadata such as a correlation ID:

```kotlin
val result =
  registerUser(
    command,
    WorkflowContext().addData(WorkflowContext.CORRELATION_ID, "corr-123"),
  )
```

## Result Projection

Use `state<T>()` when the public output comes from the final workflow state:

```kotlin
result(state<RegisteredUser>()) { state ->
  UserDto(state.id, state.email)
}
```

Use `event<T>()` when the public output comes from the last emitted event of that type:

```kotlin
result(event<UserRegisteredEvent>()) { event ->
  UserDto(event.userId, event.email)
}
```

Use a custom projection only when the output needs a combination of state, events, or context:

```kotlin
result(
  UseCaseResultProjection { result, useCaseName ->
    result.requireState<RegisteredUser>(useCaseName).map { state ->
      UserDto(state.id, state.email)
    }
  }
)
```

## Defining Workflow Steps

A workflow step turns one typed state into the next. The lightest way to write one is the `workflow` function. Its block runs inside an Arrow `Raise<WorkflowError>`, so failures use `ensure`, `ensureNotNull`, `raise`, or `bind()`:

```kotlin
val validateOrder = workflow<OrderDraft, ValidatedOrder>("validate-order") { draft ->
  ensure(draft.items.isNotEmpty()) { WorkflowError.ValidationError("Order has no items") }
  emit(OrderValidated(draft.orderId))     // record a domain event
  put(Keys.PRIORITY, draft.isPriority)    // add typed data to the context for later steps
  ValidatedOrder(draft.orderId, draft.items)
}
```

Thrown exceptions become `WorkflowError.ExceptionError`, exactly as with class-based steps. Subclass `Workflow<I, O>` instead when a step needs its own type, such as one that is injected by a DI container.

## Composing Steps

| DSL method | Use when |
| --- | --- |
| `startWith { input -> ... }` | Convert public input into the first `WorkflowState`. Must be first and called once. |
| `then(workflow)` | Run the next workflow after the previous state is available. |
| `thenIf(workflow) { result -> ... }` | Run a workflow only when the current result satisfies a condition. |
| `parallel { then(...) }` | Run independent side-effect workflows and preserve the current state. |
| `parallelJoin(...) { ... }` | Run 2 through 8 independent state-producing workflows and merge their outputs into a new state. |
| `parallelJoin(..., ParallelErrorPolicy.FailFast) { ... }` | Cancel remaining parallel-join branches on the first failure. |
| `thenLaunch(workflow, timeout = ...)` | Start side-effect work without blocking the main chain. |
| `awaitLaunched()` | Await launched side-effect work later and merge its events/context. |
| `result(state<T>()) { ... }` | Project public output from the final workflow state. Required for `useCase`. |
| `result(event<T>()) { ... }` | Project public output from the last emitted event of type `T`. |
| `result(UseCaseResultProjection { ... })` | Project public output from detailed state, events, and context. |
| `logging { ... }` | Add explicit success/failure fields to debug finish logs. |

For deeper API notes, see [Workflow Reference](docs/reference.md).

## Errors

Workflow steps return `Either<WorkflowError, WorkflowResult<State>>`. The `useCase` DSL can either preserve `WorkflowError`:

```kotlin
val useCase: UseCase<Input, WorkflowError, Output> =
  useCase(name = "Example") {
    startWith { input -> Either.Right(InitialState(input.id)) }
    then(SomeWorkflow())
    result(state<FinalState>()) { it.toOutput() }
  }
```

Or map errors to an application-specific type:

```kotlin
val useCase: UseCase<Input, ApplicationError, Output> =
  useCase(
    name = "Example",
    errors = { error -> error.toApplicationError() },
  ) {
    startWith { input -> Either.Right(InitialState(input.id)) }
    then(SomeWorkflow())
    result(state<FinalState>()) { it.toOutput() }
  }
```

Errors raised inside a step arrive wrapped in `WorkflowError.ExecutionContextError`, which records the workflow that failed. Use `rootCause()` to match on the error the step actually raised:

```kotlin
errors = { error ->
  when (val cause = error.rootCause()) {
    is WorkflowError.DomainError -> ApplicationError(cause.code)
    is WorkflowError.ValidationError -> ApplicationError("invalid")
    else -> ApplicationError("unexpected")
  }
}
```

The `errors` mapper should be total and non-throwing. For the error model and failure behavior, see [Workflow Reference](docs/reference.md#error-handling).

## Observability And Data Safety

Use cases emit debug-level SLF4J logs when they start and finish. By default these logs include safe operational metadata only:

- use case name
- success or failure outcome
- duration
- emitted event count
- executed workflow count

They do not log command input, workflow state, event payloads, projected output, or error payloads unless you explicitly project fields. Keep `name` stable and free of user data.

Global formatting can be configured once at application startup:

```kotlin
useCaseLoggingGlobalConfig {
  outcomeStyle(OutcomeStyle.Plain)
  defaultSummaryFields(DefaultSummaryFields.Enabled)
}
```

The global options control only formatting and safe built-in metadata:

- `outcomeStyle(OutcomeStyle.Plain)` keeps `outcome=success` and `outcome=failure` uncolored; `OutcomeStyle.AnsiColor` colors only the outcome value for local console logs.
- `defaultSummaryFields(DefaultSummaryFields.Enabled)` includes `durationMs`, `events`, and `workflows`; `DefaultSummaryFields.Disabled` leaves only `name`, `outcome`, and explicit fields.
Log fields render as stable `key=value` pairs.

Per-use-case logging fields are explicit and type-safe:

```kotlin
val registerUser = useCase(
  name = "RegisterUser",
  errors = ::toRegisterUserError
) {
  startWith { command -> Either.Right(RegistrationDraft(command.email, command.password)) }
  then(CreateUserWorkflow(users))

  result(state<RegisteredUser>()) { state ->
    RegisterUserResponse(state.id)
  }

  logging {
    success(state<RegisteredUser>()) { state ->
      field("userId", state.id)
    }

    failure { error: RegisterUserError ->
      field("reason", error.reason)
      field("retryable", error.retryable)
    }
  }
}
```

Success logging supports `state<T>()` and `event<T>()` projections. Failure logging is typed to the mapped public error `E`.
If a logging projection is missing its expected state/event or throws while collecting fields, the use case result is unchanged and the logging failure is reported separately.
Explicit logging fields cannot use the reserved built-in names `name`, `outcome`, `durationMs`, `events`, or `workflows`.

`toSummary(...)` is available for metrics or custom logging. Error messages are redacted by default; only set `includeErrorMessages = true` when the values are known to be safe.

## Lower-Level Workflow Use Cases

Most callers should use `useCase`. Use `workflowUseCase` only when internal code needs workflow-native results such as final state, all emitted events, or execution context:

```kotlin
val workflow = workflowUseCase<RegisterUserCommand>(name = "RegisterUser") {
  startWith { command -> Either.Right(RegistrationDraft(command.email, command.password)) }
  then(CreateUserWorkflow(users))
}

val detailed = workflow.executeDetailed(command)
```

## More Documentation

- [Make the Use Cases of Your App Explicit](docs/make-use-cases-explicit.md) explains the design problem this library addresses.
- [Design Guidance](docs/design-guidance.md) covers when to use this library and how to structure workflows.
- [Workflow Reference](docs/reference.md) covers DSL methods, errors, context, logging, and lower-level APIs.
- [Migrating to v0.8.0 Use Case Logging](docs/migrations/0.8.0-use-case-logging.md) explains how to adopt the new logging DSL.
- [Migrating from v0.6.0 to v0.7.0](docs/migrations/0.7.0-use-cases.md) explains the breaking API changes.
