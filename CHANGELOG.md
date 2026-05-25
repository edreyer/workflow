# Changelog

## 0.7.0 - Unreleased

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
