# Changelog

## 0.2.0 - 2026-01-12

### Added
- `parallelJoin` overloads (2-8) with a typed merge function and DSL helpers.
- `ParallelErrorPolicy` to choose between wait-all and fail-fast join behavior.
- `parallelJoin` tests covering ordering, event extraction errors, wait-all error selection, and fail-fast cancellation.
- Pricing example using `parallelJoin` in `OrderProcessingWorkflow` and README snippets.
- Gradual state-passing workflow DSL anchored by `WorkflowState`, `UseCaseEvents`, `startWith { ... }`, `then`, `thenIf`, and `parallel`, plus coverage showing state-typed pipelines (new `StatePassingExampleTest` / updated `OrderProcessingWorkflow`).
- Tests showcasing the new state model (`WorkflowChainTest`, `WorkflowDslTest`, `WorkflowUtilsTest`, `ParallelJoinTest`, `WorkflowDomainTest`, `WorkflowExecuteTest`) under the rewritten APIs.

### Changed
- `WorkflowInput` is now a regular interface (no longer sealed) to allow external implementations.
- `Workflow.execute` avoids double-wrapping `ExecutionContextError` values.
- Removed the auto-mapping helpers and accompanying primers in favor of explicit state transitions and typed inputs/outputs for every workflow.
- `WorkflowResult` is generic over a `WorkflowState`, adds `mergePrevious`, and now flows through `WorkflowStep`.

### Documentation
- Added `parallelJoin` usage to README core DSL docs, basic example, and best-practices parallel section.
