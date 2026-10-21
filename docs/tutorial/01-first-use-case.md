# 1. Your First Use Case

Code: [`Ch01FirstUseCase.kt`](../../src/test/kotlin/io/liquidsoftware/workflow/tutorial/Ch01FirstUseCase.kt)

A shop customer places an order: a list of products and quantities. The shop prices it and confirms the total. That is the use case we will grow through this tutorial.

## Three Kinds Of Types

A use case has three kinds of data, and it helps to give each its own types:

```kotlin
// Input: what the caller sends.
data class PlaceOrder(val customerId: String, val items: List<LineItem>)
data class LineItem(val sku: String, val quantity: Int)

// State: what the steps pass to each other.
data class Cart(val customerId: String, val items: List<LineItem>)
data class PricedCart(val cart: Cart, val total: Long)

// Output: what the caller gets back.
data class OrderConfirmation(val customerId: String, val total: Long)
```

The input belongs to your API, the output belongs to your API, and the state belongs to the use case. Keeping them apart means you can change how the use case works without changing what callers see. None of them needs a library supertype.

## The Use Case

```kotlin
val prices = mapOf("book" to 1_500L, "pen" to 250L)

val placeOrder: UseCase<PlaceOrder, Failure<Nothing>, OrderConfirmation> =
  useCase("PlaceOrder") {
    startWith { command -> Cart(command.customerId, command.items) }
      .then("price-cart") { cart ->
        PricedCart(cart, cart.items.sumOf { prices.getValue(it.sku) * it.quantity })
      }
      .result { priced -> OrderConfirmation(priced.cart.customerId, priced.total) }
  }
```

Read it top to bottom:

1. `useCase("PlaceOrder")` names the use case. The name shows up in logs and summaries, so keep it stable and free of user data.
2. `startWith` turns the input into the first state, a `Cart`.
3. `.then("price-cart") { }` is a step. It receives the current state and returns the next one, a `PricedCart`. The id `"price-cart"` names the step in logs, summaries, and error reports.
4. `.result { }` turns the final state into the output.

Each call returns a new pipeline typed by its current state. If a step expected a `PricedCart` but the state were still a `Cart`, the code would not compile.

The type `UseCase<PlaceOrder, Failure<Nothing>, OrderConfirmation>` reads as: takes a `PlaceOrder`, fails with a `Failure<Nothing>`, returns an `OrderConfirmation`. `Nothing` says that no step raises an error on purpose. Chapter 2 changes that. Kotlin needs this declared type, or explicit type arguments on `useCase`, to know the input type inside `startWith`.

## Calling It

A use case is a suspend function that returns an Arrow `Either`: `Left` for a failure, `Right` for the output.

```kotlin
val result = placeOrder(PlaceOrder("c-1", listOf(LineItem("book", 2), LineItem("pen", 1))))
// Either.Right(OrderConfirmation("c-1", 3_250))

val message = placeOrder(command).fold(
  ifLeft = { failure -> "failed: $failure" },
  ifRight = { confirmation -> "charged ${confirmation.total}" },
)
```

The tests call use cases inside `runTest` from `kotlinx-coroutines-test`. In an application you call them from a controller, a message handler, or anywhere else that can suspend.

## What You Learned

- A use case is a value with a name, a typed input, a typed error, and a typed output.
- `startWith`, then steps, then `result`. The compiler checks that each step accepts the state before it.
- Calling a use case returns an `Either`.

Next: [Typed errors](02-typed-errors.md).
