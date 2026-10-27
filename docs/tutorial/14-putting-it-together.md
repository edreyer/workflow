# 14. Putting It Together

Code: [`Ch14PuttingItTogether.kt`](../../src/test/kotlin/io/liquidsoftware/workflow/tutorial/Ch14PuttingItTogether.kt)

This chapter assembles the whole order use case from the earlier chapters, wires it into an application, and tests it and its callers. Open the code file for the complete program; this page walks through its shape.

## The Use Case

```kotlin
fun placeOrder(services: Services, config: UseCaseConfig): UseCase<PlaceOrder, ApiError, OrderDto> =
  useCase("PlaceOrder", errors = ::toApiError, config = config) {
    startWith { command ->
      ensure(command.items.isNotEmpty()) { OrderError.EmptyCart }
      Cart(command.orderId, command.customerId, command.items, command.cardToken, command.express)
    }
      .parallelJoin(loadCustomer(services.customers), priceItems(services.catalog)) { customer, total ->
        PricedOrder(this, customer, total)
      }
      .thenIf(memberDiscount) { order -> order.customer.member }
      .then(chargeCard(services.gateway))
      .then(fulfil(services.mailer))
      .result { shipment -> OrderDto(shipment.paid.order.cart.orderId, shipment.paid.order.total, shipment.trackingNumber) }
      .logging {
        success { (shipment) -> field("express", shipment.paid.order.cart.express) }
      }
  }
```

It reads as the business process: validate the cart, load the customer and price the items at once, apply the member discount, charge the card, fulfil. Every line names a step, and the details live elsewhere:

- `loadCustomer` and `priceItems` are reusable steps over plain domain services that raise `OrderError` (chapters 3 and 4).
- `chargeCard` adds a timeout and retries to a `PaymentError` step, emits `OrderPaid`, and adapts its errors with `mapError` (chapters 4, 5 and 10):

  ```kotlin
  fun chargeCard(gateway: PaymentGateway): Workflow<PricedOrder, PaidOrder, OrderError> =
    workflow<PricedOrder, PaidOrder, PaymentError>("charge-card") { order -> ... }
      .timeout(2.seconds) { PaymentError.TimedOut }
      .retry(transient)
      .mapError { OrderError.PaymentFailed(it) }
  ```

- `fulfil` is a reusable pipeline that branches on shipping speed and launches the receipt email, adapting the mail step to the shipment with `mapInput` (chapters 6, 7, 9 and 11):

  ```kotlin
  fun fulfil(mailer: Mailer): Pipeline<PaidOrder, OrderError, Shipment> =
    pipeline<PaidOrder, OrderError>()
      .branch(ifTrue = expressShipping, ifFalse = standardShipping) { paid -> paid.order.cart.express }
      .thenLaunch(sendReceipt(mailer).mapInput { shipment: Shipment -> shipment.paid.order.customer }, timeout = 5.seconds)
  ```

- `toApiError` maps every failure, including each `PaymentError`, to an HTTP-style error with nested exhaustive `when`s (chapter 2).

## Wiring It Into An Application

The use case is built by a function of its dependencies. With plain constructors, call it at startup. When a dependency injection container wants one type per use case, delegate to the built use case:

```kotlin
class PlaceOrderUseCase(services: Services, config: UseCaseConfig) :
  UseCase<PlaceOrder, ApiError, OrderDto> by placeOrder(services, config)
```

Callers depend on the `UseCase` type, not on how it is built, and pass request metadata as context:

```kotlin
class OrderController(private val placeOrder: UseCase<PlaceOrder, ApiError, OrderDto>) {
  suspend fun post(command: PlaceOrder, requestId: String): Pair<Int, Any> =
    placeOrder(command, WorkflowContext.of(WorkflowContext.CORRELATION_ID to requestId)).fold(
      ifLeft = { error -> error.status to error.code },
      ifRight = { dto -> 201 to dto },
    )
}
```

## Testing

Test at three levels, each with plain functions:

1. **Domain functions** with `either { }`, without the library (chapter 3).
2. **Steps** by calling them: `priceItems(catalog)(cart)` returns the state and events, or the `Failure` (chapter 4).
3. **Use cases** with fake services, asserting on the mapped result. Register an `onSummary` listener to check which steps ran:

   ```kotlin
   val result = PlaceOrderUseCase(services, config)(PlaceOrder("o-1", "alice", listOf(LineItem("book", 2)), "tok", express = true))
   // Either.Right(OrderDto("o-1", 2_700, "EXP-ch-2700")), after one retried charge
   ```

And callers of a use case don't need its services at all. `UseCase` is a `fun interface`, so a test stands in a lambda:

```kotlin
val controller = OrderController(UseCase { _, _ -> Either.Left(ApiError(503, "payment-unavailable")) })
controller.post(command, "req-1") // 503 to "payment-unavailable"
```

## Where To Go Next

- [Recipes](../recipes/README.md) show the use case behind Ktor and Spring Boot, with metrics, transactions and Java callers.
- [Workflow Reference](../reference.md) covers every method and its edge cases.
- [Design Guidance](../design-guidance.md) covers when to use the library, how to size steps, and what to test.
- [Make the Use Cases of Your App Explicit](../make-use-cases-explicit.md) explains the problem the library solves.
