# Changelog

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
