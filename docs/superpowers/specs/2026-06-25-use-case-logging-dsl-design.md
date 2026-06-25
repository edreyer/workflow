# Use Case Logging DSL Design

## Goal

Add an intuitive logging capability to the `useCase` DSL so application authors can log useful, structured outcome fields for both success and failure paths without dumping whole commands, workflow states, events, outputs, or errors.

The design keeps the existing safe default invocation logs and adds explicit logging projections for details that are known to be safe and useful.

## API Shape

The DSL supports global defaults plus per-use-case logging configuration.

Global defaults apply to every `useCase` unless overridden:

```kotlin
useCaseLoggingGlobalConfig {
  outcomeStyle(OutcomeStyle.Plain)
  defaultSummaryFields(DefaultSummaryFields.Enabled)
  fieldRendering(FieldRendering.KeyValue)
}
```

Use cases can add outcome-specific fields:

```kotlin
val registerUser = useCase(
  name = "RegisterUser",
  errors = ::toRegisterUserError
) {
  startWith { command -> Either.Right(RegistrationDraft(command.email, command.password)) }
  then(CreateUserWorkflow(users))

  result(state<RegisteredUser>()) { state ->
    RegisterUserResponse(state.userId)
  }

  logging {
    success(state<RegisteredUser>()) { state ->
      field("userId", state.userId)
    }

    failure { error: RegisterUserError ->
      field("type", error::class.simpleName)
      field("reason", error.reason)
      field("retryable", error.retryable)
    }
  }
}
```

This follows the existing `result(state<T>())` and `result(event<T>())` style: success logging can project from the final workflow state or last event, while failure logging runs after `WorkflowError` is mapped into the public typed error `E`.

## Behavior

Every use case continues to emit the current start and finish debug logs through `io.liquidsoftware.workflow.UseCase`.

When logging DSL fields are configured, the finish log includes:

- `name`
- `outcome`
- `durationMs`
- default summary fields such as event count and workflow count when enabled
- explicit success fields when the use case succeeds
- explicit failure fields when the use case returns `Either.Left`

Per-use-case fields extend global defaults. Precedence is simple: global fields are evaluated first, use-case fields are evaluated second, and duplicate field names from the use case replace global values.

## Safety Rules

The library must not log complete command, state, event, output, or error objects by default.

All detailed fields are opt-in through `field(name, value)`. The field name is developer-supplied and should be stable. Values are rendered with normal parameterized SLF4J arguments rather than string concatenation.

Failure logging sees the typed public error `E`, not the internal `WorkflowError`, so applications can decide which error state is safe to expose.

Success logging supports:

- `success(state<T>()) { ... }`
- `success(event<T>()) { ... }`

Success projections are type-constrained to `T : WorkflowState` or `T : Event`, matching the existing `result(state<T>())` and `result(event<T>())` APIs. The compiler verifies the projection type category, while the library verifies at runtime that the current use case actually ended with that state type or emitted that event type.

Failure logging is compile-time typesafe for the current use case's mapped public error type `E`. A `useCase<I, RegisterUserError, O>` accepts `failure { error: RegisterUserError -> ... }` and rejects failure loggers for unrelated error types.

Projection failures in logging must not change the use-case result. If a logging projection throws or cannot find its expected state/event, the library emits a small debug log about the logging projection failure and returns the original use-case result.

## Global Configuration Options

Global configuration controls common formatting and safe operational metadata. It does not configure domain-specific state, event, output, or typed error fields. Those fields belong in each use case's `logging { ... }` block.

### outcomeStyle

`outcomeStyle` controls how `outcome=success` and `outcome=failure` are rendered.

```kotlin
enum class OutcomeStyle {
  Plain,
  AnsiColor
}
```

The default is `OutcomeStyle.Plain`:

```text
outcome=success
outcome=failure
```

`OutcomeStyle.AnsiColor` colors only the outcome value:

```text
outcome=<green>success</green>
outcome=<red>failure</red>
```

The structured key remains `outcome`, so existing logs remain searchable. ANSI color is useful for local console logs, but it is disabled by default because escape sequences can make CI logs, JSON logs, Sentry breadcrumbs, and centralized log searches harder to work with.

### defaultSummaryFields

`defaultSummaryFields` controls whether the built-in operational fields are emitted automatically.

```kotlin
enum class DefaultSummaryFields {
  Disabled,
  Enabled
}
```

The default is `DefaultSummaryFields.Enabled`, which includes:

- `durationMs`
- `events`
- `workflows`

These fields are safe because they are operational counts and timings rather than command, state, event, output, or error payloads.

`DefaultSummaryFields.Disabled` emits only the required base fields and any explicitly configured logging fields:

- `name`
- `outcome`

### fieldRendering

`fieldRendering` controls how configured fields are appended to the final log message.

```kotlin
enum class FieldRendering {
  KeyValue
}
```

The default is `FieldRendering.KeyValue`:

```text
UseCase invocation finished name=RegisterUser outcome=failure durationMs=12 events=0 workflows=1 errorType=Validation reason=MissingEmail retryable=false
```

The first version supports only `KeyValue`. JSON rendering is intentionally out of scope because logger encoders and application logging infrastructure usually own JSON serialization, escaping, and nesting.

## Implementation Hook

The right implementation point is `UseCaseBuilder.buildUseCase(...)`, but public `useCase` execution will no longer use `workflowUseCase.executeProjected(...)`. `executeProjected(...)` returns only the projected result, so it hides the detailed final state needed by `logging { success(state<T>()) { ... } }`.

Instead, public `useCase` execution should:

1. run the workflow chain through the existing detailed internal path
2. apply the configured public `result(...)` projection
3. map `WorkflowError` to the typed public error `E`
4. evaluate logging projections against the detailed workflow result on success and the typed public error on failure
5. emit one finish log with the existing summary fields plus configured logging fields
6. return the original final `Either<E, O>`

This gives the logging code:

- the configured use-case name
- the original detailed workflow result for state/event success projections
- the projected public output
- the typed public error `E`
- the final `Either<E, O>` outcome

The existing low-level `WorkflowUseCase` methods remain safe and generic. They do not learn about typed public errors. Any shared start/finish logging helpers that are currently private can move behind an internal logger abstraction so both low-level workflow use cases and public DSL use cases keep consistent log formatting.

## Tests

Add tests that cover:

- global default logging applies to use cases without local logging blocks
- local logging fields appear on success from `state<T>()`
- local logging fields appear on success from `event<T>()`
- local failure fields receive the mapped typed error `E`
- local fields override duplicate global field names
- logging projections do not alter `Either.Right` or `Either.Left` results
- default logs still do not include command, state, event, output, or error payloads
- ANSI outcome coloring is disabled by default and only colors the outcome value when enabled

## Documentation

Update the README observability section and reference docs with:

- safe defaults
- global configuration
- per-use-case logging examples
- the rule that detailed log fields are explicit projections
- ANSI outcome coloring behavior
