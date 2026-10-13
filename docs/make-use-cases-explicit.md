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
val processOrder: UseCase<ProcessOrderCommand, Failure<OrderError>, OrderReceipt> = useCase(name = "ProcessOrder") {
  startWith { command ->
    OrderDraft(
      orderId = command.orderId,
      customerId = command.customerId,
      items = command.items,
      shippingAddress = command.shippingAddress,
    )
  }
    .then(validateOrder)
    .parallelJoin(calculateSubtotal, calculateTax) { subtotal, tax ->
      PricedOrder(
        order = this, // the validated order
        subtotal = subtotal.amount,
        tax = tax.amount,
        total = subtotal.amount + tax.amount,
      )
    }
    .then(checkInventory(inventory)) // records inventoryAvailable on the order
    .then(processPayment(payments))
    .thenIf(prepareShipment(shipping)) { order -> order.inventoryAvailable }
    .result { order -> order.toReceipt() }
}
```

This reads like the use case itself:

1. Start from a command.
2. Validate the order.
3. Calculate subtotal and tax in parallel.
4. Check inventory, then process the payment.
5. Prepare shipment only if the items are in stock.

## Advantages

- Explicit use cases: the business flow lives in one place instead of being inferred from service calls.
- Typed state transitions: each workflow step declares what it accepts and what it returns, and the compiler checks that each step accepts the previous step's output.
- Composable steps: build complex behavior from small workflows with clear responsibilities.
- Built-in flow control: sequence, branch, and parallelize without ad hoc orchestration code.
- Predictable error handling: workflows raise your own sealed error types instead of relying on implicit exception flow, and the boundary maps them exhaustively.
- Context and events: carry request metadata such as a correlation ID through the use case, and record domain events as it runs.
- Better testability: test individual workflows in isolation and test the full use case as a composed unit.

If your service layer is turning into a web of hidden business logic, `workflow` gives you a way to model what your application does directly in code.

## Use Cases At Application Boundaries

Defining the workflow is only part of the problem. Real applications also need to expose that workflow at a module, web, CLI, or job boundary.

The `useCase` builder handles the boundary directly:

- accept public input
- define the workflow steps
- choose whether the public result comes from the final state, an emitted event, or the context
- map each `Failure` into a boundary error type
- map the final internal result into a public output

That means the same use case stays explicit all the way out to the application edge:

```kotlin
val registerUser: UseCase<RegisterUserCommand, ApplicationError, UserDto> =
  useCase(
    name = "RegisterUser",
    errors = ::toApplicationError,
  ) {
    startWith { command -> command.toValidatedState() }
      .then(hashPassword(passwordHasher))
      .then(persistUser(userRepository, userIdGenerator))
      .result { state -> state.user.toDto() }
  }
```

The workflow stays visible. The boundary stays typed. The separate adapter layer disappears.

## Callable Use Cases

The core boundary abstraction is:

```kotlin
fun interface UseCase<in I, out E, out O> {
  suspend operator fun invoke(input: I, context: WorkflowContext): Either<E, O>

  suspend operator fun invoke(input: I): Either<E, O> =
    invoke(input, WorkflowContext())
}
```

Use `useCase` when application code needs a callable use case with public input, boundary errors, and public output.

## State Projection

Use `useCase(name, errors = ...) { ... }` with `.result { state -> ... }` when the public result comes from the final workflow state and errors should be mapped to an application type.

```kotlin
val registerUser: UseCase<RegisterUserCommand, ApplicationError, UserDto> =
  useCase(
    name = "RegisterUser",
    errors = ::toApplicationError,
  ) {
    startWith { command -> command.toValidatedState() }
      .then(hashPassword(passwordHasher))
      .then(persistUser(userRepository, userIdGenerator))
      .result { state -> state.user.toDto() }
  }
```

If callers can work with the `Failure` itself, omit `errors = ...`:

```kotlin
val registerUser: UseCase<RegisterUserCommand, Failure<RegistrationError>, UserDto> =
  useCase(name = "RegisterUser") {
    startWith { command -> command.toValidatedState() }
      .then(hashPassword(passwordHasher))
      .then(persistUser(userRepository, userIdGenerator))
      .result { state -> state.user.toDto() }
  }
```

The `errors = ...` mapper is expected to be total and non-throwing. It receives a `Failure.Raised` with your error exactly as it was raised, or a `Failure.Unexpected` for an exception. If you want to keep the `Failure`, omit it.

## Event Projection

Use `requireEvent<EventType>()` inside `.result { }` when the public result is driven by an emitted event.

It returns the last emitted event of the requested type, or fails the use case with `Failure.Unexpected`, caused by a `WorkflowCompositionException`, when none was emitted.

```kotlin
val registerUserCreatedEvent: UseCase<RegisterUserCommand, ApplicationError, UserCreatedEvent> =
  useCase(
    name = "RegisterUser",
    errors = ::toApplicationError,
  ) {
    startWith { command -> command.toValidatedState() }
      .then(hashPassword(passwordHasher))
      .then(persistUser(userRepository, userIdGenerator))
      .result { requireEvent<UserCreatedEvent>() }
  }
```

## Passing Workflow Context

If the boundary needs correlation or request metadata, invoke the contextual overload:

```kotlin
val result =
  registerUser(
    RegisterUserCommand(...),
    WorkflowContext.of(WorkflowContext.CORRELATION_ID to "corr-123"),
  )
```

If you do not supply context, the use case starts with an empty `WorkflowContext`.

Taken together, this lets `workflow` model not only the internal orchestration of a business flow, but also the typed boundary that real applications need to expose that flow cleanly.
