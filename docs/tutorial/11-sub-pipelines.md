# 11. Reusable Pipelines

Code: [`Ch11SubPipelines.kt`](../../src/test/kotlin/io/liquidsoftware/workflow/tutorial/Ch11SubPipelines.kt)

The shop now has a second use case: reorder a previous order. It starts differently, but once the order is paid, fulfilment is the same: check the items, pack, ship. Rather than copy those steps, share them.

## `pipeline<S, E>()`

`pipeline<S, E>()` starts a sequence of steps that takes state `S` and raises `E`, outside any use case. Build it with the usual methods:

```kotlin
val fulfilOrder: Pipeline<PaidOrder, OrderError, Shipment> =
  pipeline<PaidOrder, OrderError>()
    .guard("check-items") { order -> ensure(order.skus.isNotEmpty()) { OrderError.EmptyOrder } }
    .then("pack") { order -> PackedOrder(order, parcels = (order.skus.size + 2) / 3) }
    .then("ship") { packed -> Shipment(packed.order.orderId, "TRACK-${packed.order.orderId}-${packed.parcels}") }
```

The type `Pipeline<PaidOrder, OrderError, Shipment>` reads as: takes a `PaidOrder`, raises `OrderError`, ends with a `Shipment`. A reusable pipeline has no `result`.

## Appending It

Pass it to `then`:

```kotlin
val placeOrder: UseCase<PlaceOrder, Failure<OrderError>, Shipment> =
  useCase("PlaceOrder") {
    startWith { command -> command }
      .then("take-payment") { command -> PaidOrder("o-${command.customerId}", command.skus) }
      .then(fulfilOrder)
      .result { shipment -> shipment }
  }

val reorder: UseCase<Reorder, Failure<OrderError>, Shipment> =
  useCase("Reorder") {
    startWith { command -> command }
      .then("load-previous-order") { command -> ... }
      .then(fulfilOrder)
      .result { shipment -> shipment }
  }
```

Its steps run as if they were written in place. Each is recorded under its own id, so a `PlaceOrder` summary lists `take-payment`, `check-items`, `pack`, `ship`. Their events join the use case's events. The pipeline's error type can be a subtype of the use case's.

## Steps Or Pipelines?

- A **step** is one unit of work with one id. Combinators such as `retry`, `parallel`, and `thenIf` take steps.
- A **reusable pipeline** is a named sequence of steps. Use it to share a sequence between use cases, or to give a long use case named sections that read like a table of contents.

A use case is not a step, and can't be appended to another one. A use case is an entry point with its own name, error mapping, and summary. To share behavior between use cases, share steps or pipelines.

## What You Learned

- `pipeline<S, E>()` builds a reusable sequence of steps.
- `then(pipeline)` appends it, and its steps keep their own ids.

Next: [Request context](12-context.md).
