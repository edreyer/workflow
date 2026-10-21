# 9. Background Work

Code: [`Ch09LaunchedWork.kt`](../../src/test/kotlin/io/liquidsoftware/workflow/tutorial/Ch09LaunchedWork.kt)

Sending the confirmation email takes a second, and saving the order shouldn't wait for it. A failed email shouldn't fail the order either. `thenLaunch` starts that kind of work and lets the pipeline carry on.

## `thenLaunch` And `awaitLaunched`

```kotlin
fun sendConfirmation(mailer: Mailer): Workflow<Order, Unit, Nothing> =
  workflow("send-confirmation") { order ->
    mailer.send(order.email)
    emit(ConfirmationEmailed(order.id))
  }

fun placeOrder(mailer: Mailer, config: UseCaseConfig = UseCaseConfig.Default): UseCase<Order, Failure<Nothing>, OrderConfirmation> =
  useCase("PlaceOrder", config = config) {
    startWith { order -> order }
      .thenLaunch(sendConfirmation(mailer), timeout = 2.seconds)
      .then("save-order") { order -> delay(200); order }
      .awaitLaunched()
      .result { order -> OrderConfirmation(order.id, emailed = events.any { it is ConfirmationEmailed }) }
  }
```

`thenLaunch` starts the step and keeps the current state. `save-order` runs while the email is being sent, so the whole use case takes one second, not 1.2.

`awaitLaunched()` waits for everything launched so far and merges its events. Use it when a later step or the result needs them, as the result does here. Without it, launched work is still awaited and merged before `result` runs.

## What Launched Work Can't Do

Launched work is for side effects that the outcome doesn't depend on:

- Its failures never fail the use case. Each failure becomes a `LaunchedFailureEvent` with the step's id and a message free of user data, and appears in `UseCaseSummary.launchedFailures` (chapter 13).
- Its output is ignored. The pipeline keeps the state it had before `thenLaunch`.
- The use case still waits for it before returning, even when a later step fails. Launched work runs inside the invocation; it is not fire-and-forget beyond it. Cancelling the caller cancels it too.

```kotlin
placeOrder(Mailer(down = true), config)(order)
// Either.Right(OrderConfirmation("o-1", emailed = false))
// summary.launchedFailures == ["thenLaunch failure send-confirmation (An exception occurred)"]
```

## Always Bound The Wait

Because the use case waits for launched work, a hung mail server would hang the order. Pass a `timeout`. A timed-out launch is reported as a launched failure:

```kotlin
.thenLaunch(sendConfirmation(Mailer(latencySeconds = 5)), timeout = 2.seconds)
// events == [LaunchedFailureEvent("send-confirmation", "thenLaunch timeout: send-confirmation")]
```

To set a default for every `thenLaunch` that passes no `timeout`, set `launchTimeout` in the config:

```kotlin
val config = useCaseConfig { launchTimeout(1.seconds) }
```

For work that must outlive the request, or survive a restart, use a message queue or an outbox instead. This library orchestrates a single request in process.

## What You Learned

- `thenLaunch(step, timeout)` starts side-effect work that later steps don't wait for.
- `awaitLaunched()` merges launched events when something later needs them.
- Launched failures and timeouts are reported, never raised. The use case waits for launched work, so bound it with a timeout.

Next: [Timeouts, retries and fallbacks](10-resilience.md).
