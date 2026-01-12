# Changelog

## 0.2.0 - 2026-01-12

### Added
- `parallelJoin` overloads (2-8) with a typed merge function and DSL helpers.
- `ParallelErrorPolicy` to choose between wait-all and fail-fast join behavior.
- `parallelJoin` tests covering ordering, event extraction errors, wait-all error selection, and fail-fast cancellation.
- Pricing example using `parallelJoin` in `OrderProcessingWorkflow` and README snippets.

### Changed
- `WorkflowInput` is now a regular interface (no longer sealed) to allow external implementations.
- `Workflow.execute` avoids double-wrapping `ExecutionContextError` values.

### Documentation
- Added `parallelJoin` usage to README core DSL docs, basic example, and best-practices parallel section.
