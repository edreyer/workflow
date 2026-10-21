# 8. Parallel Steps

Code: [`Ch08Parallel.kt`](../../src/test/kotlin/io/liquidsoftware/workflow/tutorial/Ch08Parallel.kt)

Loading the customer, pricing the items, and checking stock are three calls that don't depend on each other. Running them one after another wastes time. This chapter runs them concurrently, then notifies the warehouse and records analytics at the same time.

## `parallelJoin`: Combine Several Results

```kotlin
val loadCustomer: Workflow<PlaceOrder, Customer, OrderError> = workflow("load-customer") { ... }
val priceItems: Workflow<PlaceOrder, Prices, Nothing> = workflow("price-items") { ... }
val checkStock: Workflow<PlaceOrder, Stock, OrderError> = workflow("check-stock") { ... }

.parallelJoin(loadCustomer, priceItems, checkStock) { customer, prices, _ ->
  Checkout(this, customer, prices.total)
}
```

`parallelJoin` runs 2 to 8 steps concurrently on the current state, then the merge block combines their outputs into the next state. The current state is `this`, here the `PlaceOrder`. Each step runs with the same input and is recorded under its own id, and the join itself is recorded as `parallelJoin(load-customer,price-items,check-stock)`. In the test, three 100 ms lookups take 100 ms together.

## `parallel`: Side Effects

```kotlin
val notifyWarehouse: Workflow<Checkout, Unit, Nothing> =
  workflow("notify-warehouse") { checkout -> delay(50); emit(WarehouseNotified(checkout.customer.id)) }

val recordAnalytics: Workflow<Checkout, Unit, Nothing> =
  workflow("record-analytics") { checkout -> delay(50); emit(AnalyticsRecorded(checkout.customer.id)) }

.parallel(notifyWarehouse, recordAnalytics)
```

`parallel` runs steps concurrently and keeps the current state. Their outputs are ignored. Events from parallel steps are merged in parameter order, whichever step finished first, so results are deterministic.

## The Use Case

```kotlin
val placeOrder: UseCase<PlaceOrder, Failure<OrderError>, Pair<OrderConfirmation, List<Event>>> =
  useCase("PlaceOrder") {
    startWith { command -> command }
      .parallelJoin(loadCustomer, priceItems, checkStock) { customer, prices, _ ->
        Checkout(this, customer, prices.total)
      }
      .parallel(notifyWarehouse, recordAnalytics)
      .result { checkout -> OrderConfirmation(checkout.customer.id, checkout.customer.tier, checkout.total) to events }
  }
```

## When A Branch Fails

A failing branch fails the use case. The `policy` parameter decides what happens to the other branches:

- `ParallelErrorPolicy.WaitAll`, the default, waits for every branch and fails with the first error in parameter order.
- `ParallelErrorPolicy.FailFast` cancels the remaining branches on the first failure. Use it when the other branches are expensive or pointless once one fails.

## Naming A Join

The top-level `parallelJoin` function builds a step instead of adding to a pipeline. Name a join to reuse it, or to test it on its own:

```kotlin
val prepareCheckout: Workflow<PlaceOrder, Checkout, OrderError> =
  parallelJoin(loadCustomer, priceItems, policy = ParallelErrorPolicy.FailFast) { customer, prices ->
    Checkout(this, customer, prices.total)
  }
```

All branches read the same, read-only request context. Only their events are merged back.

## What You Learned

- `parallelJoin(a, b, ...) { x, y -> }` runs steps concurrently and merges their outputs into the next state.
- `parallel(a, b, ...)` runs side-effect steps concurrently and keeps the state.
- Events merge in parameter order. `WaitAll` and `FailFast` decide how a failure stops the others.

Next: [Background work](09-launched-work.md).
