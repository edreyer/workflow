# Changelog

## Unreleased

Breaking. See [Migrating to v0.10.0](docs/migrations/0.10.0.md).

- Steps are functions. `Workflow` is now a class the library builds, so users no longer implement it or override `WorkflowStepScope.execute`. Write business logic as ordinary functions (with `context(_: Raise<E>)` when they can fail) and wire them in with `workflow(id) { }` or the new inline `Pipeline.then(id) { }`.
- Added `Pipeline.guard(id) { }`, an inline pass-through check.
- `WorkflowExecution.workflowName` is always the step `id`. It used to be the class name for class-based steps.

## 0.9.1 - 2026-10-13

- Arrow (`arrow-core` and the `arrow-stack` platform) is now an `api` dependency. `Either` and `Raise` are part of the public API, but 0.9.0 published Arrow with `runtime` scope, so a consumer that did not declare Arrow itself could not compile against the library.
- A `startWith` block that returns an `Either` now fails the use case with `WorkflowError.CompositionError` telling you to call `.bind()`. Before, the `Either` itself became the first state, which only the next step's types could catch.
- Added `guard(id) { input -> }`, a step that checks its input with `ensure` or `raise` and passes it on unchanged.

## 0.9.0 - 2026-10-12

Breaking release. See [Migrating to v0.9.0 Typed Pipelines](docs/migrations/0.9.0-typed-pipelines.md) for before/after examples of every change.

- Type-safe pipelines: `startWith { }` returns a `Pipeline<I, E, S>`, and `.then`, `.thenIf`, `.parallel`, `.parallelJoin`, `.thenLaunch`, `.awaitLaunched` and `.result` are chained on it. A step that does not accept the current state, or a missing `.result`, is now a compile error instead of a runtime `CompositionError`.
- `Workflow<I, O>` is an interface with a single `suspend fun WorkflowStepScope.execute(input: I): O`. Steps return their state and fail through Arrow's `Raise` (`ensure`, `raise`, `bind()`), emit events with `emit`, and add context with `put`. `workflow(id) { }` builds a step from a lambda. Run a step on its own with `step(input, context)`.
- `startWith` and `.result` blocks are `Raise` scopes too. `.result` receives the typed final state, with `events`, `context`, the full `result`, and `requireEvent<T>()` in scope. This replaces `state<T>()`, `event<T>()` and `UseCaseResultProjection`.
- Errors are no longer wrapped: `ExecutionContextError` and `ChainError` are removed, and mappers receive the raised error. `WorkflowError` is an open interface with `code` and `message`, so applications can raise their own sealed error types. `rootCause()` is deprecated and returns the error unchanged. `CompositionError` no longer carries an exception.
- `parallelJoin` merge functions receive the current state as `this`. The error policy is a named parameter that defaults to `WaitAll`.
- `.parallel(a, b)` replaces `parallel<S> { then(a); then(b) }`. Side-effect steps may return any type.
- Launched work still pending at the end of the pipeline is awaited and merged before `.result`, so its events and failures are no longer dropped. `thenLaunch` takes a `kotlin.time.Duration` timeout.
- `WorkflowContext` uses identity-based `Key<T>("name")` keys that keep generic types; `context[key]`, `with`, `plus` and `WorkflowContext.of(...)` replace `addData`, `get`, `combine` and the string-keyed API. Execution history moved out of the context into `UseCaseSummary.executions`.
- `workflowUseCase`, `WorkflowUseCase`, `UseCaseResult`, `UseCaseEvents` and the `execute*` methods are removed; `useCase` with `.result { }` covers each case.
- Per-use-case logging is chained after `.result` and typed to the final `WorkflowResult<S>` and the mapped error.
- `toSummary` is replaced by `useCaseLoggingGlobalConfig { onSummary { summary -> } }`. `UseCaseSummary.duration` is a monotonic `kotlin.time.Duration`, `RootErrorInfo` gains `code`, and `withErrorMessages()` replaces `includeErrorMessages`. `WorkflowExecution` gains a monotonic `duration`.
- `UseCaseCommand` and `WorkflowState` are deprecated and no longer required. Any type can be input or state.
- Removed the `kotlin-reflect` dependency and reflective input-type discovery (`WorkflowUtils`, `Workflow.inputClass`).
- A step's own timeout (`withTimeout` inside the step) is now a `WorkflowError.ExceptionError` instead of cancelling the use case. Caller cancellation still propagates.
- Parallel branches and launched work only merge the context keys they changed, so a stale copy of an untouched key no longer overwrites a newer value.
- Summaries name the step behind the error that was returned, include events from parallel branches that succeeded before another branch failed, record `thenLaunch` timeouts as failed executions, and still reach listeners when the `errors` mapper throws.
- Published artifacts target Java 17 (bytecode and Gradle `org.gradle.jvm.version` metadata). 0.8.0 declared Java 25, so Gradle projects on Java 17 or 21 could not resolve it. The build itself still uses the JDK 25 toolchain.
- DSL receivers are marked `@WorkflowDsl`, so a nested block cannot silently call a member of an enclosing one.
- `LaunchedFailureEvent.workflowId` names the launched workflow instead of `launched-<index>`.
- `thenLaunch` only reports its own timeout as a timeout; caller cancellation propagates.
- Removed unused runtime dependencies (`jsoup`, `arrow-fx-coroutines`, `arrow-resilience`) and the unused serialization plugin, so consumers no longer pull them in.
- Enabled explicit API mode and Kotlin ABI validation (`api/workflow.api`).
- Tests use `kotlinx-coroutines-test`; the unused kotest dependencies were removed.

## 0.8.0 - 2026-06-28

- Updated Kotlin to 2.4.0.
- Verified the build with the existing Java 25 toolchain.
- Added startup-time `useCaseLoggingGlobalConfig { ... }` for configuring use case invocation log formatting.
- Added `OutcomeStyle` and `DefaultSummaryFields` options for plain/ANSI outcomes and built-in summary fields.
- Added a type-safe per-use-case `logging { ... }` DSL for explicit success and failure log fields.
- Added success log projections from `state<T>()` and `event<T>()`; missing projection data is reported as a logging projection failure without changing the use case result.
- Added failure logging typed to the use case's mapped public error type `E`.
- Preserved safe default logging: command input, workflow state, event payloads, projected output, and error payloads are not logged unless explicitly projected.
- Ensured configured logging fields are evaluated only when debug logging is enabled.
- Reserved built-in log field names (`name`, `outcome`, `durationMs`, `events`, `workflows`) so explicit fields cannot spoof canonical invocation metadata.
- Changed public `useCase` execution to collect detailed workflow results internally so configured logging fields can be emitted without duplicate low-level invocation logs.
- Documented the new logging DSL in the README and reference docs.
- Added a migration guide for adopting v0.8.0 use case logging.
- Updated Arrow to 2.2.3.
- Migration note: ordinary `useCase { ... }` consumers do not need changes; consumers with explicit `UseCaseBuilder<I, O>` extension/helper signatures should update them to include the new error type parameter.

## 0.7.0 - 2026-05-28

- Replaced the v0.6.0 typed-boundary API with a single callable `UseCase<I, E, O>` model.
- Changed the primary `useCase { ... }` DSL to define public input, workflow steps, error mapping, and output projection in one place.
- Removed the v0.6.0 typed-boundary adapter helpers (`TypedUseCase`, `ContextualTypedUseCase`, `toStateUseCase(...)`, and `toEventUseCase(...)`); boundary mapping now lives in `useCase { ... }` through `startWith`, `errors`, and `result`.
- Added `state<StateType>()` and `event<EventType>()` result sources for explicit state and event projection inside use case definitions.
- Renamed the lower-level workflow-native execution model to `WorkflowUseCase` and exposed it through `workflowUseCase { ... }`.
- Moved workflow-native execution methods such as `executeDetailed(...)`, `executeProjected(...)`, `executeForEvent(...)`, and `executeForState(...)` onto `WorkflowUseCase`; application-facing `UseCase<I, E, O>` values are invoked directly.
- Fixed `thenLaunch` so launched side-effect workflows run concurrently with subsequent steps until `awaitLaunched()` drains them.
- Changed `toSummary` to redact root error messages by default, while preserving safe domain error codes and allowing explicit opt-in with `includeErrorMessages = true`.
- Added a migration guide for upgrading v0.6.0 typed-boundary use cases to the new primary use case DSL.

## 0.6.0 - 2026-03-14

- Added `TypedUseCase` and `ContextualTypedUseCase` for exposing workflow-backed application/module use cases without repetitive adapter classes.
- Added `UseCase.toStateUseCase(...)` overloads for raw and mapped error handling with explicit input/output adaptation.
- Added `UseCase.toEventUseCase(...)` overloads for raw and mapped error handling with event-based projection semantics aligned with `executeForEvent(...)`.

## 0.5.0 - 2026-03-09

- Added `UseCaseResult` to preserve final state, emitted events, and execution context for successful use case execution.
- Added `UseCase.executeDetailed(...)`, `executeProjected(...)`, `executeForEvent(...)`, and `executeForState(...)` to make the library easier to use at application boundaries.
- Added `WorkflowError.DomainError` for structured domain/business failures without flattening everything into `ExecutionError`.
- Added `BaseEvent` as a convenience base type with generated `id` and `timestamp`.
- Extended telemetry helpers so `toSummary(...)` works directly with detailed use case results and derives context/execution data from the result when available.
- Preserved backwards compatibility for existing direct `UseCase` subclasses by adapting legacy `execute(...)` implementations through `EventOnlyState`.

## 0.4.0 - 2026-01-13

- Added telemetry helpers (`UseCaseSummary`, `toSummary`) for consistent logging/metrics of use case results.
- Added `LaunchedFailureEvent` emission on launched side-effect failures/timeouts; failure data surfaces via events/context.

## 0.3.0 - 2026-01-13

- Added `thenLaunch` (fire-and-forget side-effect workflows with optional timeout) and `awaitLaunched` to drain launched work and merge their metadata without failing the chain; failures are surfaced via `LaunchedFailureEvent` and context data.

## 0.2.2 - 2026-01-13

- Added top-level DSL `parallelJoin(...)` overloads so joins can be declared directly (no `then(...)` wrapper); README and examples updated accordingly.

## 0.2.1 - 2026-01-13

- Added an explicit input-type hint on `Workflow` and propagate it through `parallelJoin` to prevent runtime composition errors while keeping inference-friendly usage.
- Removed redundant variance projections in `parallelJoin` internals (compiler warning cleanup).

## 0.2.0 - 2026-01-12

### Added
- `parallelJoin` overloads (2-8) with a typed merge function and DSL helpers.
- `ParallelErrorPolicy` to choose between wait-all and fail-fast join behavior.
- Pricing example using `parallelJoin` in `OrderProcessingWorkflow` and README snippets.
- Gradual state-passing workflow DSL anchored by `WorkflowState`, `UseCaseEvents`, `startWith { ... }`, `then`, `thenIf`, and `parallel`.

### Changed
- `WorkflowInput` is now a regular interface (no longer sealed) to allow external implementations.
- `Workflow.execute` avoids double-wrapping `ExecutionContextError` values.
- Removed the auto-mapping helpers and accompanying primers in favor of explicit state transitions and typed inputs/outputs for every workflow.
- `WorkflowResult` is generic over a `WorkflowState`, adds `mergePrevious`, and now flows through `WorkflowStep`.

### Documentation
- Added `parallelJoin` usage to README core DSL docs, basic example, and best-practices parallel section.
