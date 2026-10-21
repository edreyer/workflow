# 6. Conditional Steps

Code: [`Ch06Branching.kt`](../../src/test/kotlin/io/liquidsoftware/workflow/tutorial/Ch06Branching.kt)

Members get 10% off, and express orders ship by courier instead of post. Both are decisions the use case should show, not bury inside a step.

## `thenIf`: Run A Step Or Skip It

```kotlin
val memberDiscount: Workflow<Order, Order, Nothing> =
  workflow("member-discount") { order -> order.copy(total = order.total - order.total / 10) }

.thenIf(memberDiscount) { order -> order.member }
```

Because the step may be skipped, it must keep the state type: `memberDiscount` takes an `Order` and returns an `Order`. A skipped step doesn't appear in the execution history.

## `branch`: Run One Of Two Steps

```kotlin
val expressShipping: Workflow<Order, ShippedOrder, Nothing> =
  workflow("express-shipping") { order -> ShippedOrder(order, carrier = "courier", days = 1) }

val standardShipping: Workflow<Order, ShippedOrder, Nothing> =
  workflow("standard-shipping") { order -> ShippedOrder(order, carrier = "post", days = 4) }

.branch(ifTrue = expressShipping, ifFalse = standardShipping) { order -> order.express }
```

Both steps produce the same next state type, `ShippedOrder`. Whichever one runs is recorded under its own id, so a summary tells you which path an order took.

## Together

```kotlin
val placeOrder: UseCase<PlaceOrder, Failure<Nothing>, OrderConfirmation> =
  useCase("PlaceOrder") {
    startWith { command -> Order(command.customerId, command.total, command.member, command.express) }
      .thenIf(memberDiscount) { order -> order.member }
      .branch(ifTrue = expressShipping, ifFalse = standardShipping) { order -> order.express }
      .result { shipped -> OrderConfirmation(shipped.order.total, shipped.carrier, shipped.days) }
  }
```

The predicates receive the current state as their parameter. Their receiver is a `WorkflowResult`, so they can also read `events` emitted so far and the request `context` (chapter 12). Keep predicates small and free of side effects; anything that does work belongs in a step.

For a decision with more than two outcomes, use a step that returns a sealed type, or a `when` inside one step. The pipeline stays a straight line that reads well.

## What You Learned

- `thenIf(step) { predicate }` runs a step only when the predicate holds. The step keeps the state type.
- `branch(ifTrue, ifFalse) { predicate }` runs one of two steps with the same output type.

Next: [Working on part of the state](07-focused-steps.md).
