# 7. Working On Part Of The State

Code: [`Ch07FocusedSteps.kt`](../../src/test/kotlin/io/liquidsoftware/workflow/tutorial/Ch07FocusedSteps.kt)

As a use case grows, its state grows too. But most steps only need a piece of it: reserving stock needs the SKUs, quoting shipping needs the address. Steps that take exactly what they need are easier to test and can be shared with other use cases, even ones in other modules.

## Steps That Know Nothing About The Use Case

```kotlin
val reserveStock: Workflow<List<String>, Reservation, OrderError> =
  workflow("reserve-stock") { skus ->
    ensure("sold-out" !in skus) { OrderError.OutOfStock }
    Reservation("r-${skus.size}")
  }

val quoteShipping: Workflow<Address, Quote, OrderError> =
  workflow("quote-shipping") { address ->
    ensure(address.country in setOf("NL", "DE")) { OrderError.CannotShipTo(address.country) }
    Quote("post", if (address.country == "NL") 495 else 895)
  }
```

## Pick The Input, Merge The Output

`then(step, input = { }) { output -> }` runs a step on part of the state. `input` picks what the step needs. The merge block receives the step's output and returns the next state, with the current state as `this`:

```kotlin
data class Checkout(val customerId: String, val address: Address, val skus: List<String>)
data class ReservedCheckout(val checkout: Checkout, val reservation: Reservation)
data class ReadyCheckout(val checkout: Checkout, val reservation: Reservation, val shippingCost: Long)

val placeOrder: UseCase<PlaceOrder, Failure<OrderError>, OrderConfirmation> =
  useCase("PlaceOrder") {
    startWith { command -> Checkout(command.customerId, command.address, command.skus) }
      .then(reserveStock, input = { checkout -> checkout.skus }) { reservation -> ReservedCheckout(this, reservation) }
      .then(quoteShipping, input = { it.checkout.address }) { quote ->
        ReadyCheckout(checkout, reservation, shippingCost = quote.cents)
      }
      .result { ready -> OrderConfirmation(ready.reservation.id, ready.shippingCost) }
  }
```

Each state type says what is known at that point. A `ReadyCheckout` always has a reservation and a shipping cost, so nothing downstream has to check for `null`. When the state type stays the same, the merge block can be a `copy`: `{ quote -> copy(shippingCost = quote.cents) }`.

The step keeps its own id, `reserve-stock`, in the execution history.

## Adapting A Step Once

To adapt a step once and pass the result around, use `mapInput` and `mapOutput`. They work like `mapError` from chapter 4 and keep the step's id:

```kotlin
val shippingCostOf: Workflow<Checkout, Long, OrderError> =
  quoteShipping
    .mapInput { checkout: Checkout -> checkout.address }
    .mapOutput { quote -> quote.cents }
```

`shippingCostOf` is a step from `Checkout` to `Long`. That matters for the combinators in the next chapters, `parallel`, `parallelJoin`, `thenIf`, and `thenLaunch`, which take a step that accepts the current state.

## What You Learned

- Write steps against the smallest input they need.
- `then(step, input = { }) { output -> }` runs a step on part of the state and builds the next state.
- `mapInput`, `mapOutput`, and `mapError` adapt a reusable step and keep its id.

Next: [Parallel steps](08-parallel-steps.md).
