# Micrometer metrics

> Compiled: the code lives in
> [`MicrometerRecipeTest.kt`](../../src/test/kotlin/io/liquidsoftware/workflow/recipes/MicrometerRecipeTest.kt)
> and runs with the build, against Micrometer 1.17.

Every use case invocation ends with a `UseCaseSummary`: the name, the outcome, the
duration, each step that ran, and, on failure, the error type and the step that raised
it. Register one listener on the application's `UseCaseConfig` and every use case gets
metrics, with nothing added to the use cases themselves.

## The listener

```kotlin
class UseCaseMetrics(private val registry: MeterRegistry) {
  fun record(summary: UseCaseSummary) {
    Timer.builder("usecase")
      .tag("usecase", summary.useCaseName)
      .tag("outcome", if (summary.succeeded) "success" else "failure")
      .tag("error", summary.rootError?.type ?: "none")
      .tag("step", summary.rootError?.workflowId ?: "none")
      .register(registry)
      .record(summary.duration.toJavaDuration())

    summary.executions.forEach { execution ->
      Timer.builder("usecase.step")
        .tag("usecase", summary.useCaseName)
        .tag("step", execution.workflowId)
        .tag("outcome", if (execution.succeeded) "success" else "failure")
        .register(registry)
        .record(execution.duration.toJavaDuration())
    }

    if (summary.launchedFailures.isNotEmpty()) {
      registry.counter("usecase.launched.failures", "usecase", summary.useCaseName)
        .increment(summary.launchedFailures.size.toDouble())
    }
  }
}
```

That gives you, per use case, a latency timer split by outcome, the failure rate by
error type and failing step, per-step latency, and a counter for background work that
failed without failing the request.

## Wiring it in

Build the config once at startup and pass it to every use case:

```kotlin
fun appConfig(registry: MeterRegistry): UseCaseConfig = useCaseConfig {
  onSummary(UseCaseMetrics(registry)::record)
}
```

In Spring Boot, take the `MeterRegistry` bean; in Ktor, the registry you installed
`MicrometerMetrics` with. See the [Spring](spring.md) and [Ktor](ktor.md) recipes.

## Things to know

- **Tag cardinality stays bounded** as long as use case names and step ids are constants,
  which the library already asks for. `rootError.type` is the raised error's class name,
  so a sealed error hierarchy gives a fixed set of values; it never holds the error's
  data. `rootError.workflowId` is `null` when `startWith` or `result` failed, hence
  `"none"`.
- **Listeners run on the caller's coroutine** after each invocation, before it returns.
  Keep them fast and non-blocking; Micrometer's meters are. A listener that throws an
  exception is logged as a warning and ignored, so it can't fail the use case.
- **Retries are recorded once.** A step wrapped in `retry` appears in `executions` once,
  and its duration covers every attempt and the waits between them.
- **Prefer this to timing around the call.** A timer around `useCase(command)` can't tell
  you which step was slow or failed.
