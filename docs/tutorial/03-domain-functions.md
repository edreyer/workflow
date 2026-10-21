# 3. Domain Functions As Steps

Code: [`Ch03DomainFunctions.kt`](../../src/test/kotlin/io/liquidsoftware/workflow/tutorial/Ch03DomainFunctions.kt)

So far the business logic lives inside the pipeline. That doesn't scale: the pipeline should read like a list of what happens, and the logic should live in ordinary code that you can test and reuse. This chapter moves pricing into a `Catalog`, adds a check that the customer isn't blocked, and passes both in as dependencies.

## Plain Kotlin And Arrow

```kotlin
sealed interface OrderError {
  data object EmptyCart : OrderError
  data class UnknownProduct(val sku: String) : OrderError
  data object CustomerBlocked : OrderError
}

class Catalog(private val prices: Map<String, Long>) {
  context(_: Raise<OrderError>)
  fun price(cart: Cart): PricedCart {
    val total = cart.items.sumOf { item ->
      val unitPrice = ensureNotNull(prices[item.sku]) { OrderError.UnknownProduct(item.sku) }
      unitPrice * item.quantity
    }
    return PricedCart(cart, total)
  }
}

class Customers(private val blocked: Set<String>) {
  fun isBlocked(customerId: String): Boolean = customerId in blocked
}
```

Neither class imports anything from this library. A function that can fail declares `context(_: Raise<OrderError>)`, a Kotlin context parameter of Arrow's `Raise` type, and fails with `ensure`, `ensureNotNull`, or `raise`. Inside such a function, import those from `arrow.core.raise.context`. The unknown product from chapter 2 is now a typed error instead of an exception.

You can test the catalog with Arrow's `either { }` alone. Give `either` its type arguments, so Kotlin knows which `Raise` to supply:

```kotlin
either<OrderError, PricedCart> { catalog.price(Cart("c-1", listOf(LineItem("mug", 1)))) }
// Either.Left(OrderError.UnknownProduct("mug"))
```

## Wiring Them In

A step block is a `Raise<OrderError>`, so it can call `catalog.price` directly. The use case becomes a function of its dependencies:

```kotlin
fun placeOrder(catalog: Catalog, customers: Customers): UseCase<PlaceOrder, ApiError, OrderConfirmation> =
  useCase("PlaceOrder", errors = ::toApiError) {
    startWith { command ->
      ensure(command.items.isNotEmpty()) { OrderError.EmptyCart }
      Cart(command.customerId, command.items)
    }
      .guard("check-customer") { cart ->
        ensure(!customers.isBlocked(cart.customerId)) { OrderError.CustomerBlocked }
      }
      .then("price-cart") { cart -> catalog.price(cart) }
      .result { priced -> OrderConfirmation(priced.cart.customerId, priced.total) }
  }
```

`guard` is a step that checks the state and passes it on unchanged, so the state after `check-customer` is still the `Cart`. Use it for authorization, invariants, and other checks that produce nothing.

Kotlin doesn't allow a function reference to a function with context parameters, so write `{ cart -> catalog.price(cart) }`, not `catalog::price`.

## Dependencies

The library has no dependency injection. A use case is a value, so give it dependencies as you would any other value: here, as parameters of a function that builds it. Build it once at startup and share it. Chapter 14 shows how this fits a DI container.

## What You Learned

- Business logic belongs in plain Kotlin functions that declare `context(_: Raise<E>)` when they can fail.
- Test those functions with `either { }`, without the library.
- Steps call them; the pipeline names each step.
- `guard` checks the state and passes it on.
- Dependencies are ordinary parameters.

Next: [Reusable steps](04-reusable-steps.md).
