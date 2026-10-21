# 5. Domain Events

Code: [`Ch05Events.kt`](../../src/test/kotlin/io/liquidsoftware/workflow/tutorial/Ch05Events.kt)

When an order is placed, other parts of the system care: billing, loyalty, analytics. Steps record those facts as domain events. This chapter creates an order, awards loyalty points, and shows two ways to use the events.

## Emitting Events

An event is any type that implements `Event`. Extend `BaseEvent` instead to get a generated `id` and `timestamp`:

```kotlin
data class OrderCreated(val orderId: String, val customerId: String) : BaseEvent()
data class LoyaltyPointsEarned(val customerId: String, val points: Int) : Event
```

A step records an event with `emit`:

```kotlin
fun createOrder(orders: Orders): Workflow<PlaceOrder, Order, Nothing> =
  workflow("create-order") { command ->
    val order = orders.create(command.customerId, command.total)
    emit(OrderCreated(order.id, order.customerId))
    order
  }

val awardPoints: Workflow<Order, Order, Nothing> =
  workflow("award-points") { order ->
    emit(LoyaltyPointsEarned(order.customerId, points = (order.total / 100).toInt()))
    order
  }
```

Events record facts. Data a later step needs goes in the state the step returns, where the compiler can check it. Here the order id is in the `Order` state, and `OrderCreated` announces that it happened.

## Reading Events In The Result

The `result` block sees every event the pipeline emitted, in order, as `events`:

```kotlin
fun placeOrder(orders: Orders): UseCase<PlaceOrder, Failure<Nothing>, OrderConfirmation> =
  useCase("PlaceOrder") {
    startWith { command -> command }
      .then(createOrder(orders))
      .then(awardPoints)
      .result { order ->
        val points = events.lastEvent<LoyaltyPointsEarned>()?.points ?: 0
        OrderConfirmation(order.id, order.total, points)
      }
  }
```

`events.lastEvent<T>()` returns the last event of type `T`, or `null`. When the pipeline always emits the event, use `requireEvent<T>()` instead, as the next example does. If no step emitted it, the use case was wired wrongly, and it fails with `Failure.Unexpected` caused by a `WorkflowCompositionException`.

## Returning Events To The Caller

A common pattern is to return the events, so the caller can publish them, for example to an outbox, after its transaction commits:

```kotlin
data class Placed(val confirmation: OrderConfirmation, val events: List<Event>)

.result { order ->
  val points = requireEvent<LoyaltyPointsEarned>().points
  Placed(OrderConfirmation(order.id, order.total, points), events)
}
```

A step run on its own returns its events too: `createOrder(orders)(command)` holds them in `WorkflowResult.events`.

When a step fails, the events of the steps that finished before it still count in the summary (chapter 13), but the caller gets only the failure.

## What You Learned

- `emit(event)` records a domain event. Events implement `Event`, or extend `BaseEvent`.
- Data for later steps goes in the state; facts go in events.
- `result` reads `events`, with `lastEvent<T>()` and `requireEvent<T>()`.

Next: [Conditional steps](06-branching.md).
