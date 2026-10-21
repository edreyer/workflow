# 13. Logs, Summaries And Metrics

Code: [`Ch13Observability.kt`](../../src/test/kotlin/io/liquidsoftware/workflow/tutorial/Ch13Observability.kt)

Because every use case has a name and every step has an id, the library can report what ran without you writing any logging code. This chapter turns on summaries for metrics, adds a log field, and shows how error messages are kept out of logs by default.

## One Config For The Application

Build a `UseCaseConfig` once at startup and pass it to each use case, like any other dependency:

```kotlin
fun appConfig(metrics: Metrics): UseCaseConfig = useCaseConfig {
  outcomeStyle(OutcomeStyle.Plain)
  defaultSummaryFields(DefaultSummaryFields.Enabled)
  onSummary { summary -> metrics.record(summary) }
}

fun placeOrder(config: UseCaseConfig): UseCase<PlaceOrder, Failure<OrderError>, OrderConfirmation> =
  useCase("PlaceOrder", config = config) { ... }
```

`outcomeStyle` chooses plain or ANSI-coloured outcomes in the debug log, and `defaultSummaryFields` turns the duration, event, and step counts in that log on or off. The values shown are the defaults. There is no global state, so each test can give a use case its own config. To adjust the application config for one use case, pass it as the base: `useCaseConfig(appConfig) { onSummary { ... } }` adds a listener and keeps the rest.

## Summaries

`onSummary` is called after every invocation, whatever the log level, with a `UseCaseSummary`:

| Property | What it tells you |
| --- | --- |
| `useCaseName`, `succeeded`, `duration` | What ran, how it ended, and how long it took. |
| `executions` | Every step that ran, with its id, timing, and outcome, including parallel and launched steps. |
| `rootError` | For a failure: the error's type, whether it was unexpected, and the `workflowId` of the step that failed. |
| `eventsCount`, `launchedFailures`, `correlationId` | Events emitted, launched work that failed (chapter 9), and the request's correlation ID (chapter 12). |

That is enough for a metric per use case and outcome, and for an alert that names the failing step:

```kotlin
class Metrics {
  fun record(summary: UseCaseSummary) {
    recorded += "${summary.useCaseName} ${if (summary.succeeded) "ok" else "failed at ${summary.rootError?.workflowId}"}"
  }
}
// "PlaceOrder ok", "PlaceOrder failed at check-limit"
```

## Redacted Error Messages

An error's message can contain user data. `OrderError.LimitExceeded` holds a customer id, for example. So `rootError.message` is `null` by default. When you know messages are safe for your environment, `withErrorMessages()` returns a copy that includes them:

```kotlin
summary.rootError                      // RootErrorInfo("LimitExceeded", unexpected = false, message = null, workflowId = "check-limit")
summary.withErrorMessages().rootError  // ... message = "LimitExceeded(customerId=c-1, limit=50000)" ...
```

## Debug Logs

Each use case also logs at debug level on the `io.liquidsoftware.workflow.UseCase` logger when it starts and finishes:

```text
UseCase invocation finished name=PlaceOrder outcome=success durationMs=12 events=0 workflows=2
```

Those logs carry only safe metadata. To add a field, chain `.logging { }` after `result`. Fields are typed to the result and the failure, and are only computed when debug logging is on:

```kotlin
.result { it }
.logging {
  success { (confirmation) -> field("orderId", confirmation.orderId) }
  failure { failure -> field("unexpected", failure is Failure.Unexpected) }
}
```

A logging block that throws never changes the use case's result.

## What You Learned

- `useCaseConfig { }` builds one config for the application; pass it to each use case.
- `onSummary` receives a `UseCaseSummary` after every invocation, with the failing step's id.
- Error messages are redacted unless you call `withErrorMessages()`.
- `.logging { }` adds explicit fields to the debug log.

Next: [Putting it together](14-putting-it-together.md).
