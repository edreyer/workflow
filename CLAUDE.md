# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

`workflow` is a single-module Kotlin library (published as `io.liquidsoftware:workflow`) providing a DSL for composing application use cases out of small, typed, testable steps. It has no application code of its own — all source lives under `src/main/kotlin/io/liquidsoftware/workflow/` and `src/test/kotlin/io/liquidsoftware/workflow/`.

## Commands

```bash
./gradlew build              # compile + test + all verification tasks
./gradlew test                # run the full test suite (JUnit 5 via Kotest runner)
./gradlew test --tests "io.liquidsoftware.workflow.WorkflowDslTest"          # single test class
./gradlew test --tests "io.liquidsoftware.workflow.WorkflowDslTest.*should merge state*"  # single test method (match by name)
./gradlew dependencyUpdates   # check for dependency updates (com.github.ben-manes.versions plugin)
```

There is no separate lint task; Kotlin compiler warnings surface during `build`/`test`. JVM toolchain is 25 (`kotlin { jvmToolchain(25) }` in `build.gradle.kts`).

Tests use Kotest assertions/runner on top of JUnit 5 (`kotlin("test")` + `kotest-runner-junit5` + `kotest-assertions-core`), plus `logback-classic` as the test-scope SLF4J backend.

## Architecture

The library is built in layers, each in its own file under `src/main/kotlin/io/liquidsoftware/workflow/`:

- **`WorkflowDomain.kt`** — core data model: `WorkflowState`, `UseCaseCommand`, `Event`/`BaseEvent`, `Key<T>` (typed context keys), `WorkflowContext` (immutable data bag + execution history that threads through a chain), `WorkflowResult<S>`, `UseCaseResult<S>`, and the `WorkflowError` sealed hierarchy (`ValidationError`, `ExecutionError`, `DomainError`, `ExceptionError`, `CompositionError`, `ExecutionContextError`, `ChainError`).
- **`Workflow.kt`** — the abstract `Workflow<I, O>` base class (single-step unit of work; subclasses override `executeWorkflow`) and `WorkflowUseCase<C>`, the lower-level workflow-native execution model with `executeDetailed`/`execute`/`executeProjected`/`executeForEvent`/`executeForState`. `Workflow.execute` wraps `executeWorkflow` with exception capture and records a `WorkflowExecution` (name/id/timings/success) into the context.
- **`WorkflowDsl.kt`** — `workflowUseCase { ... }` builder (`WorkflowChainBuilderFactory`) that assembles a `WorkflowUseCase` from an ordered list of `WorkflowStep`s: `startWith` (required, first), `then`/`thenIf` (sequential/conditional steps), `parallel { then(...) }` (side-effect-only fan-out), `thenLaunch`/`awaitLaunched` (fire-and-forget work joined later), plus the `parallelJoin` overloads re-exported as builder methods. Each step receives and returns a `WorkflowResult<WorkflowState>`, casting state via reflection (`WorkflowUtils.getWorkflowInputClass`) to the next workflow's declared input type — this is how the chain stays type-erased internally but type-checked at each `then(...)` call site.
- **`ParallelJoin.kt`** — free-standing `parallelJoin(...)` functions (2–8 branches, with `crossinline merge` and optional `ParallelErrorPolicy.WaitAll`/`FailFast`) that run several state-producing workflows against the same input concurrently and merge their outputs into one new state via `ParallelJoinWorkflow`. `FailFast` uses `select` to cancel remaining branches on first failure.
- **`UseCaseDsl.kt`** — the public-facing `useCase { ... }` DSL built on top of `WorkflowChainBuilderFactory` via `UseCaseBuilder`. Adds `result(state<T>()) { ... }` / `result(event<T>()) { ... }` / a custom `UseCaseResultProjection` to convert final workflow state/events into a public output type, an optional `errors: (WorkflowError) -> E` mapper, and `logging { success(...) ; failure(...) }` for typed, opt-in log fields. Produces a callable `UseCase<I, E, O>` (a `fun interface` with `invoke(input, context)`).
- **`UseCaseInvocationLogging.kt`** / **`UseCaseTelemetry.kt`** — SLF4J debug-level start/finish logging (`logUseCaseStarted`/`logUseCaseFinished`) that includes only safe metadata (name, outcome, duration, event count, workflow count) unless explicit `logging { }` fields are added; global formatting is configured once via `useCaseLoggingGlobalConfig { ... }`. `toSummary()` extension functions build a `UseCaseSummary` for external metrics/logging, redacting error messages by default.

**Data flow through a chain**: `startWith` converts the public `UseCaseCommand` into an initial `WorkflowState`, wrapped as `WorkflowResult`. Each subsequent step's `Workflow.execute` runs, and `WorkflowResult.mergePrevious` folds emitted events and combined `WorkflowContext` from the previous step into the next. Errors short-circuit via `Either` (Arrow) and, once inside a `Workflow.execute`, get wrapped as `WorkflowError.ExecutionContextError` carrying the failing `WorkflowExecution`. At the end, `useCase`'s configured `result(...)` projection extracts public output from the final state or last matching event, and the configured `errors` mapper (if any) converts `WorkflowError` to the application error type.

**Testing convention**: `src/test/kotlin/io/liquidsoftware/workflow/OrderProcessingWorkflow.kt` is a shared example domain (not a `*Test.kt` file) used across several test files (`WorkflowChainTest`, `StatePassingExampleTest`, `ParallelJoinTest`, etc.) to exercise the DSL end-to-end — check it first when writing new workflow-chain tests rather than inventing a new example domain.

## Documentation

- `docs/make-use-cases-explicit.md` — architectural motivation for the library.
- `docs/design-guidance.md` — when to use this library and how to structure workflows.
- `docs/reference.md` — full DSL reference (steps, errors, context, logging, lower-level APIs).
- `docs/migrations/` — breaking-change migration guides between versions.
- `Readme.md` mirrors much of `docs/reference.md` with runnable examples; keep both in sync when changing public DSL surface.

## Working Conventions (from AGENTS.md)

- Prefer the smallest, simplest design that fully satisfies the requirements; do not add speculative abstractions, config, or extension points.
- Touch only the files/lines required for the task; preserve unrelated changes.
- If Serena MCP tools are available, activate the project and prefer them for symbol navigation and safe symbol-level edits; use shell tools for docs, config, logs, and broad text search.
- Favor algebraic data types (sealed hierarchies, `Either`) over flags/nullable fields — this is already the pattern used throughout (`WorkflowError`, `WorkflowStep`, `Either<WorkflowError, ...>`). Represent expected/recoverable failures as typed `Either` results rather than exceptions; exceptions remain for unexpected/unrecoverable failures.
