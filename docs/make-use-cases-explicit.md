# Make the Use Cases of Your App Explicit

Most applications start with a clean service layer and end up with business logic spread across service classes, helper methods, and conditionals. The result is familiar: use cases become implicit. To understand what "process order" or "register user" actually does, you have to trace call chains across the codebase.

`workflow` takes a different approach. Instead of hiding business behavior inside service methods, it lets you define a use case directly as a typed workflow composed from small, focused steps. The flow is visible in one place, the transitions are explicit, and each step stays isolated and testable.

## Why not services alone?

Services are good at technical capabilities:

- store data
- send email
- call an API
- publish a message

But use cases are business flows:

- what happens when a user registers
- what must happen before an order ships
- which steps can run in parallel
- which conditions allow the next step to execute

As an application grows, those business flows often get fragmented across services. `workflow` brings them back together and makes them explicit.

## Example

```kotlin
val processOrder: UseCase<ProcessOrderCommand> = useCase {
  startWith { command ->
    OrderDraftState(
      orderId = command.orderId,
      customerId = command.customerId,
      items = command.items,
      totalAmount = command.totalAmount
    ).right()
  }

  then(ValidateOrderWorkflow("validate-order"))

  parallelJoin(
    CalculateSubtotalWorkflow("calc-subtotal"),
    CalculateTaxWorkflow("calc-tax"),
  ) { subtotal, tax ->
    PricingState(
      orderId = subtotal.orderId,
      customerId = subtotal.customerId,
      items = subtotal.items,
      totalAmount = subtotal.totalAmount,
      shippingAddress = subtotal.shippingAddress,
      subtotal = subtotal.subtotal,
      tax = tax.tax,
      total = subtotal.subtotal + tax.tax
    )
  }

  parallel {
    then(CheckInventoryWorkflow("check-inventory"))
    then(ProcessPaymentWorkflow("process-payment"))
  }

  thenIf(PrepareShipmentWorkflow("prepare-shipment")) { result ->
    val paymentSuccessful = when (result.getFromEvent(PaymentProcessedEvent::payment)) {
      is SuccessfulPayment -> true
      else -> false
    }

    result.context.getTypedData<Boolean>("inventoryAvailable") == true && paymentSuccessful
  }
}
```

This reads like the use case itself:

1. Start from a command.
2. Validate the order.
3. Calculate subtotal and tax in parallel.
4. Check inventory and process payment in parallel.
5. Prepare shipment only if the business conditions are met.

## Advantages

- Explicit use cases: the business flow lives in one place instead of being inferred from service calls.
- Typed state transitions: each workflow step declares what it accepts and what it returns.
- Composable steps: build complex behavior from small workflows with clear responsibilities.
- Built-in flow control: sequence, branch, and parallelize without ad hoc orchestration code.
- Predictable error handling: workflows return typed results instead of relying on implicit exception flow.
- Shared context and events: carry metadata and emitted domain events through the use case.
- Better testability: test individual workflows in isolation and test the full use case as a composed unit.

If your service layer is turning into a web of hidden business logic, `workflow` gives you a way to model what your application does directly in code.
