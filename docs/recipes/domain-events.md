# Publishing domain events

> Compiled: the code lives in
> [`DomainEventsRecipeTest.kt`](../../src/test/kotlin/io/liquidsoftware/workflow/recipes/DomainEventsRecipeTest.kt)
> and runs with the build.

Steps `emit` domain events, and the use case collects them. The library doesn't deliver
them anywhere: that is your application's job, and the right way depends on what
listens. Two patterns cover most applications.

## Publish after the use case succeeds

When listeners are in process, or losing an event on a crash is acceptable, publish
once the whole use case has succeeded. A failed use case publishes nothing, so
listeners never see an `OrderPlaced` for an order whose payment then failed.

`result { }` has every collected event in scope. Return them next to the output, and let
a small decorator publish them and hand back the output alone:

```kotlin
data class WithEvents<out O>(val output: O, val events: List<Event>)

fun <I, E, O> UseCase<I, E, WithEvents<O>>.publishingTo(bus: EventBus): UseCase<I, E, O> =
  UseCase { input, context ->
    this(input, context).map { (output, events) ->
      events.forEach { bus.publish(it) }
      output
    }
  }
```

```kotlin
fun placeOrder(bus: EventBus): UseCase<PlaceOrder, Failure<OrderError>, OrderDto> =
  useCase<PlaceOrder, OrderError, WithEvents<OrderDto>>("PlaceOrder") {
    startWith { command -> command }
      .then(createOrder)
      .then(awardPoints)
      .result { order -> WithEvents(OrderDto(order.id), events) }
  }.publishingTo(bus)
```

`EventBus` is your own one-method interface over Spring's `ApplicationEventPublisher`, a
Kafka producer, or a `MutableSharedFlow`. Callers still see a plain
`UseCase<PlaceOrder, Failure<OrderError>, OrderDto>`.

Publishing happens outside the use case, so a publish that throws reaches the caller as
an exception, after the work is done. Catch it in `publish` if that would be wrong for
your callers.

## Write an outbox in the same transaction

When another service must see every event, publishing after the fact isn't enough: the
process can stop between the database commit and the publish. Use a transactional
outbox. The step that writes the business data also writes the event to an outbox
table, in the same transaction, and a separate relay sends outbox rows to the broker.

```kotlin
fun saveOrder(db: Database) = workflow<PlaceOrder, Order, OrderError>("save-order") { command ->
  ensure(command.total > 0) { OrderError.EmptyOrder }
  val order = Order(command.orderId, command.total)
  val placed = OrderPlaced(order.id, order.total)
  db.transaction {
    orders += order
    outbox += placed
  }
  emit(placed)
  order
}
```

The step still `emit`s the event, so `result { }`, `requireEvent`, and the summary's
event count see it like any other.

Keep the transaction inside one step, as here, rather than around the whole use case.
[Transactions](transactions.md) explains why.

## Which events are collected

- Events from every step that ran, including parallel branches and launched work: the
  use case waits for launched work before `result`.
- Launched work that fails or times out adds a `LaunchedFailureEvent` instead of failing
  the use case. Filter it out before publishing if your listeners shouldn't see it.
- A step wrapped in `retry` contributes only the successful attempt's events, and one
  wrapped in `recover` drops the events of the failed run.
- A failed use case returns only the mapped error. If you need to know what happened
  before the failure, use the [summary](micrometer.md) or the logs, not the events.
