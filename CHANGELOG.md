# Changelog

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
