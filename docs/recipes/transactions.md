# Transactions

> Not compiled: this recipe is checked by reading, not by the build. It targets Spring
> Framework 6 or 7 and Exposed 1.x.

Put a database transaction inside the one step that writes, not around the whole use
case.

## Why not around the use case

A transaction that spans the use case looks tidy, `@Transactional` on the method that
calls it, but it goes wrong in three ways:

- **It holds a connection and its locks across everything else**: the payment call, the
  retries and their backoff, the email. A slow gateway becomes a full connection pool.
- **It doesn't reach every step.** JDBC transactions in Spring and Exposed are bound to
  the thread. A coroutine can resume on another thread after it suspends, and parallel
  branches and launched work run in coroutines of their own. Some writes would quietly
  run outside the transaction.
- **It can't roll back what matters.** A captured payment or a sent email stays done
  when the database rolls back. Those need a typed error and a compensating step
  (`recover`), whatever the transaction does.

So: steps that talk to other systems run outside any transaction, and the step that
persists the result owns a short one. When other systems must hear about the change,
write an outbox row in that same transaction; see [Publishing domain events](domain-events.md).

## Spring, blocking JDBC

```kotlin
fun saveOrder(tx: TransactionTemplate, orders: OrderRepository, outbox: OutboxRepository) =
  workflow<PaidOrder, SavedOrder, OrderError>("save-order") { paid ->
    val placed = OrderPlaced(paid.orderId, paid.total)
    withContext(Dispatchers.IO) {
      tx.executeWithoutResult {
        orders.save(paid.toEntity())
        outbox.append(placed)
      }
    }
    emit(placed)
    SavedOrder(paid.orderId)
  }
```

`withContext(Dispatchers.IO)` keeps the blocking JDBC work, and the thread-bound
transaction, on one thread for the duration of the block. `TransactionTemplate` makes
the boundary visible where `@Transactional` on a `suspend` function would not work
with a JDBC transaction manager.

## Spring, R2DBC

With a reactive transaction manager the transaction lives in the coroutine context, so
use `TransactionalOperator` in the step:

```kotlin
fun saveOrder(tx: TransactionalOperator, orders: OrderRepository, outbox: OutboxRepository) =
  workflow<PaidOrder, SavedOrder, OrderError>("save-order") { paid ->
    val placed = OrderPlaced(paid.orderId, paid.total)
    tx.executeAndAwait {
      orders.save(paid.toEntity())
      outbox.append(placed)
    }
    emit(placed)
    SavedOrder(paid.orderId)
  }
```

## Exposed

Exposed 1.x's `suspendTransaction` (from `org.jetbrains.exposed.v1.jdbc.transactions`)
runs a JDBC transaction whose block can call `suspend` functions. Give it a dispatcher
with `withContext`, since it takes none:

```kotlin
fun saveOrder() = workflow<PaidOrder, SavedOrder, OrderError>("save-order") { paid ->
  val placed = OrderPlaced(paid.orderId, paid.total)
  withContext(Dispatchers.IO) {
    suspendTransaction {
      Orders.insert { it[id] = paid.orderId; it[total] = paid.total }
      Outbox.insert { it[eventType] = "OrderPlaced"; it[orderId] = placed.orderId }
    }
  }
  emit(placed)
  SavedOrder(paid.orderId)
}
```

With the R2DBC driver, use `suspendTransaction` from `org.jetbrains.exposed.v1.r2dbc.transactions`
instead, without `withContext`.

## Raising inside a transaction

Check what you can before you open the transaction, so it stays short. When a check has
to run inside it, such as "this order number is not taken yet", `raise` works there too:
Arrow raises by throwing a `CancellationException`, which Spring's templates and
Exposed treat as a failure and roll back, and the use case then sees the typed error.

Don't retry a step that opened a transaction unless the whole transaction is safe to
repeat. `retry` re-runs the step from its input, so a transaction that committed before a
later failure in the same step would commit twice.
