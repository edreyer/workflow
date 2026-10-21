# 2. Typed Errors

Code: [`Ch02TypedErrors.kt`](../../src/test/kotlin/io/liquidsoftware/workflow/tutorial/Ch02TypedErrors.kt)

An empty cart, or a line with a quantity of zero, should be rejected. This chapter gives the use case its own error type and shows how failures reach callers.

## Your Own Error Type

Errors are your own types. A sealed hierarchy works best, because the compiler can then check that you handle every case:

```kotlin
sealed interface OrderError {
  data object EmptyCart : OrderError
  data class InvalidQuantity(val sku: String) : OrderError
}
```

## Raising Errors

`startWith` and every step block are Arrow [`Raise`](https://arrow-kt.io/learn/typed-errors/working-with-typed-errors/) scopes for the use case's error type. Fail with `ensure`, `ensureNotNull`, or `raise`:

```kotlin
startWith { command ->
  ensure(command.items.isNotEmpty()) { OrderError.EmptyCart }
  command.items.firstOrNull { it.quantity <= 0 }?.let { raise(OrderError.InvalidQuantity(it.sku)) }
  Cart(command.customerId, command.items)
}
```

`ensure(condition) { error }` raises the error when the condition is false. A raise stops the pipeline: later steps don't run. Import `ensure` from `arrow.core.raise`.

## Two Kinds Of Failure

A failed use case reports a `Failure<E>`, which is one of two cases:

- `Failure.Raised(error)`: a step raised one of your errors on purpose.
- `Failure.Unexpected(cause)`: something threw an exception. In this chapter's code, `prices.getValue(sku)` throws for an unknown product, and that becomes `Failure.Unexpected` with the `NoSuchElementException` as its cause.

Keeping them apart matters. A raised error is a business outcome you can show the user. An unexpected failure is a bug or an outage, and you usually report it as an internal error. Chapter 3 turns the unknown product into a raised error, which is where it belongs.

## Mapping Errors For Callers

Callers often need their own error type, such as an HTTP status. Pass an `errors` mapper, and the use case returns its result instead of the `Failure`:

```kotlin
data class ApiError(val status: Int, val code: String)

fun toApiError(failure: Failure<OrderError>): ApiError =
  when (failure) {
    is Failure.Raised -> when (val error = failure.error) {
      OrderError.EmptyCart -> ApiError(400, "empty-cart")
      is OrderError.InvalidQuantity -> ApiError(400, "invalid-quantity:${error.sku}")
    }
    is Failure.Unexpected -> ApiError(500, "internal")
  }

val placeOrder: UseCase<PlaceOrder, ApiError, OrderConfirmation> =
  useCase("PlaceOrder", errors = ::toApiError) {
    startWith { command -> ... }
      .then("price-cart") { cart -> ... }
      .result { priced -> OrderConfirmation(priced.cart.customerId, priced.total) }
  }
```

The mapper's parameter type, `Failure<OrderError>`, tells the use case which errors its steps may raise. Both `when` expressions are exhaustive with no `else`, so adding a case to `OrderError` is a compile error until the mapper handles it. Keep the mapper total and non-throwing; an exception it throws goes straight to the caller.

Without a mapper, callers get the `Failure` itself:

```kotlin
val placeOrderRaw: UseCase<PlaceOrder, Failure<OrderError>, OrderConfirmation> =
  useCase("PlaceOrder") { ... }

placeOrderRaw(PlaceOrder("c-1", emptyList())) // Either.Left(Failure.Raised(OrderError.EmptyCart))
```

## What You Learned

- Errors are your own sealed types, raised with Arrow's `ensure`, `ensureNotNull`, and `raise`.
- The first failure stops the pipeline.
- `Failure.Raised` is an error you raised; `Failure.Unexpected` is an exception.
- The `errors` mapper turns every failure into the caller's error type, exhaustively.

Next: [Domain functions as steps](03-domain-functions.md).
