# 10. Timeouts, Retries And Fallbacks

Code: [`Ch10Resilience.kt`](../../src/test/kotlin/io/liquidsoftware/workflow/tutorial/Ch10Resilience.kt)

The payment gateway is a network call. Sometimes it's slow, sometimes it's busy, sometimes the client throws. And sometimes it declines the card, which is not an outage at all. This chapter makes the charge step resilient without hiding those differences.

## The Errors

```kotlin
sealed interface PaymentError {
  data object Declined : PaymentError
  data object Unavailable : PaymentError
  data object TimedOut : PaymentError
}
```

## Three Combinators

`timeout`, `retry`, and `recover` wrap a reusable step and return a step with the same id:

```kotlin
val transient: Schedule<Failure<PaymentError>, *> =
  Schedule.exponential<Failure<PaymentError>>(200.milliseconds)
    .and(Schedule.recurs(2))
    .doWhile { failure, _ ->
      failure is Failure.Unexpected || failure == Failure.Raised(PaymentError.Unavailable) ||
        failure == Failure.Raised(PaymentError.TimedOut)
    }

fun chargeCard(gateway: PaymentGateway): Workflow<Order, PaidOrder, PaymentError> =
  workflow<Order, PaidOrder, PaymentError>("charge-card") { order ->
    PaidOrder(order, Payment.Charged(gateway.charge(order)))
  }
    .timeout(2.seconds) { PaymentError.TimedOut }
    .retry(transient)
    .recover { order, error ->
      if (error == PaymentError.Declined && order.total >= 10_000) PaidOrder(order, Payment.PendingReview) else raise(error)
    }
```

### `timeout`

`timeout(2.seconds) { PaymentError.TimedOut }` cancels the step after two seconds and raises `TimedOut`, a typed error you can map and retry. A `withTimeout` inside the step's own block would instead fail as `Failure.Unexpected`.

### `retry`

`retry(schedule)` runs the step again while an Arrow [`Schedule`](https://arrow-kt.io/learn/resilience/retry-and-repeat/) allows it. The schedule receives each `Failure`, so it can tell an exception from a raised error:

- `Schedule.exponential(200.milliseconds)` waits 200 ms, then 400 ms, and so on.
- `.and(Schedule.recurs(2))` stops after two retries, three attempts in all.
- `.doWhile { failure, _ -> }` retries only exceptions, `Unavailable`, and `TimedOut`. A decline is a decision, so retrying it would only annoy the bank.

When the schedule stops, the step fails with the last failure. Only the successful attempt's events are kept, and the step is recorded once, with its total duration.

### `recover`

`recover { input, error -> }` handles a raised error: return an output instead, or raise an error. Here a large declined order goes to manual review, and everything else stays an error. `recover` never sees exceptions, so a bug can't turn into a fallback.

`recover` can also change the error type. Raising an `OrderError` from it gives a `Workflow<Order, PaidOrder, OrderError>`, which saves a separate `mapError`.

## Order Matters

The combinators read outside-in, like function calls:

- `.timeout(2.seconds).retry(s)` gives each attempt two seconds.
- `.retry(s).timeout(5.seconds)` gives all attempts five seconds together.
- `.retry(s).recover { }` recovers only after the retries are used up.

## Testing With Virtual Time

Schedule delays use `delay`, so tests under `runTest` skip them. The test gateway answers each call with a scripted response:

```kotlin
val gateway = PaymentGateway(PaymentGateway.Busy, PaymentGateway.Crash)
placeOrder(gateway)(order) // Either.Right(Payment.Charged("ch-o-1-3")) after three calls

placeOrder(PaymentGateway(PaymentGateway.Decline))(order) // Either.Left(Failure.Raised(PaymentError.Declined)), one call
```

## What You Learned

- `timeout(duration) { error }` turns a slow step into a typed error.
- `retry(schedule)` retries what the schedule allows, using Arrow's `Schedule`. Retry outages, not decisions.
- `recover { input, error -> }` handles raised errors with a fallback or a new error.
- The order of the combinators decides what each one covers.

Next: [Reusable pipelines](11-sub-pipelines.md).
