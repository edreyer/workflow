# 4. Reusable Steps

Code: [`Ch04ReusableSteps.kt`](../../src/test/kotlin/io/liquidsoftware/workflow/tutorial/Ch04ReusableSteps.kt)

Inline steps are fine when a step is used once. When a step is shared between use cases, tested on its own, or passed to the combinators of later chapters, define it once with `workflow`. This chapter adds a bulk discount and a card payment.

## Defining A Step

```kotlin
fun priceCart(catalog: Catalog): Workflow<Cart, PricedCart, OrderError> =
  workflow("price-cart") { cart -> catalog.price(cart) }
```

`Workflow<I, O, E>` is a named step from input `I` to output `O` that can raise `E`. The block is the same as an inline step's. A step with dependencies is a function that returns the step, like a use case.

A step that never fails declares `Nothing` as its error type. It fits a pipeline of any error type:

```kotlin
val bulkDiscount: Workflow<PricedCart, PricedCart, Nothing> =
  workflow("bulk-discount") { priced ->
    if (priced.cart.items.sumOf { it.quantity } >= 10) priced.copy(total = priced.total * 9 / 10) else priced
  }
```

## Adapting Another Module's Errors

Payments are a separate module with their own errors. They know nothing about orders:

```kotlin
sealed interface PaymentError {
  data object Declined : PaymentError
  data object InsufficientFunds : PaymentError
}

class PaymentGateway {
  context(_: Raise<PaymentError>)
  suspend fun charge(cardToken: String, amount: Long): String { ... }
}

fun chargeCard(gateway: PaymentGateway): Workflow<PricedCart, PaidOrder, PaymentError> =
  workflow("charge-card") { priced ->
    PaidOrder(priced.cart, priced.total, gateway.charge(priced.cart.cardToken, priced.total))
  }
```

Every step in a pipeline must raise the pipeline's error type, a subtype of it, or `Nothing`. `chargeCard` raises `PaymentError`, so passing it to an `OrderError` pipeline is a compile error. `mapError` adapts it, and keeps its id:

```kotlin
sealed interface OrderError {
  data class UnknownProduct(val sku: String) : OrderError
  data class PaymentFailed(val reason: String) : OrderError
}

fun placeOrder(catalog: Catalog, gateway: PaymentGateway): UseCase<PlaceOrder, Failure<OrderError>, OrderConfirmation> =
  useCase("PlaceOrder") {
    startWith { command -> Cart(command.customerId, command.items, command.cardToken) }
      .then(priceCart(catalog))
      .then(bulkDiscount)
      .then(chargeCard(gateway).mapError { error -> OrderError.PaymentFailed(error.toString()) })
      .result { paid -> OrderConfirmation(paid.cart.customerId, paid.total, paid.chargeId) }
  }
```

Now the use case reads as a list of named steps: price the cart, apply the bulk discount, charge the card.

## Testing A Step On Its Own

Call a step like a function, after `import io.liquidsoftware.workflow.invoke`. It returns the new state, the events it emitted, and the context it ran with, or the `Failure`:

```kotlin
val result = bulkDiscount(PricedCart(cart, 2_500))
result.map { it.state } // Either.Right(PricedCart(cart, 2_250))

priceCart(catalog)(cartWithAMug) // Either.Left(Failure.Raised(OrderError.UnknownProduct("mug")))
```

## What You Learned

- `workflow<I, O, E>(id) { }` defines a named, reusable step.
- `Nothing` is the error type of a step that never fails.
- `mapError` adapts a step written for another error type.
- A step can be called directly in a test.

Next: [Domain events](05-events.md).
