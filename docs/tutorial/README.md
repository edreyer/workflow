# Tutorial

This tutorial builds one use case, placing an order in an online shop, from a single step up to a pipeline with typed errors, parallel lookups, retries, background work, and metrics. Each chapter adds one idea to what the previous chapters built.

Every example is a test. The code for chapter N lives in [`src/test/kotlin/io/liquidsoftware/workflow/tutorial/`](../../src/test/kotlin/io/liquidsoftware/workflow/tutorial/) as `ChNN*.kt`, in its own package, and runs with the build, so the examples always compile against the current API. Open the file next to the chapter to see the imports and the whole program, or run a chapter with:

```bash
./gradlew test --tests "io.liquidsoftware.workflow.tutorial.ch01.*"
```

Domain functions in the examples use Kotlin [context parameters](https://kotlinlang.org/docs/context-parameters.html), which need a Kotlin version that supports them (this project builds with Kotlin 2.4). Tests use [`kotlinx-coroutines-test`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/) for `runTest`. See [Installation](../../Readme.md#installation) for the dependency.

## Chapters

| # | Chapter | You learn |
| --- | --- | --- |
| 1 | [Your first use case](01-first-use-case.md) | `useCase`, `startWith`, an inline `then`, `result`, and calling a use case. |
| 2 | [Typed errors](02-typed-errors.md) | Sealed error types, `ensure` and `raise`, `Failure.Raised` and `Failure.Unexpected`, and the `errors` mapper. |
| 3 | [Domain functions as steps](03-domain-functions.md) | Business logic in plain Kotlin and Arrow, `context(_: Raise<E>)`, `guard`, and passing dependencies. |
| 4 | [Reusable steps](04-reusable-steps.md) | `workflow(id) { }`, `Nothing` as an error type, running a step on its own, and `mapError`. |
| 5 | [Domain events](05-events.md) | `emit`, `Event` and `BaseEvent`, and reading events in `result` with `lastEvent` and `requireEvent`. |
| 6 | [Conditional steps](06-branching.md) | `thenIf` and `branch`. |
| 7 | [Working on part of the state](07-focused-steps.md) | `then(step, input) { merge }`, `mapInput`, and `mapOutput`. |
| 8 | [Parallel steps](08-parallel-steps.md) | `parallelJoin`, `parallel`, and `ParallelErrorPolicy`. |
| 9 | [Background work](09-launched-work.md) | `thenLaunch`, `awaitLaunched`, launch timeouts, and `LaunchedFailureEvent`. |
| 10 | [Timeouts, retries and fallbacks](10-resilience.md) | `timeout`, `retry` with an Arrow `Schedule`, and `recover`. |
| 11 | [Reusable pipelines](11-sub-pipelines.md) | `pipeline<S, E>()` to share a sequence of steps between use cases. |
| 12 | [Request context](12-context.md) | `WorkflowContext`, `Key`, the correlation ID, and context parameters. |
| 13 | [Logs, summaries and metrics](13-observability.md) | `useCaseConfig`, `onSummary`, `logging { }`, and redacted error messages. |
| 14 | [Putting it together](14-putting-it-together.md) | A complete use case, wiring it into an application, and testing its callers. |

## The Idea In One Paragraph

A use case is a named value with a typed input, a typed error, and a typed output. Inside it, a pipeline lists the steps in order. Each step takes the current state and returns the next one, and the compiler checks that each step accepts what the previous one produced. Steps call ordinary functions that hold the business logic. The library runs the steps, stops at the first failure, collects domain events, and reports what ran. [Make the Use Cases of Your App Explicit](../make-use-cases-explicit.md) explains why that shape helps.

When you finish, the [Workflow Reference](../reference.md) covers every method in detail, and [Design Guidance](../design-guidance.md) covers how to structure larger applications.
