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

## Expose Use Cases At Application Boundaries

Defining the workflow is only part of the problem. Real applications also need to expose that workflow at a module, web, CLI, or job boundary.

That boundary usually needs to do four things:

- map public input into an internal `UseCaseCommand`
- choose state or event projection
- map `WorkflowError` into a boundary error type
- map the final internal result into a public output

`workflow` now supports that directly with `TypedUseCase` and `ContextualTypedUseCase`.

That means the same use case can stay explicit all the way out to the application edge:

```kotlin
val registerUser: ContextualTypedUseCase<RegisterUserCommand, ApplicationError, UserDto> =
  useCase<RegisterUserInternalCommand> {
    startWith { command -> command.toValidatedState() }
    then(HashPasswordStep(passwordHasher))
    then(PersistUserStep(userRepository, userIdGenerator))
  }.toStateUseCase(
    inputMapper = ::RegisterUserInternalCommand,
    errorMapper = WorkflowError::toApplicationError,
    outputMapper = { state: RegisteredUserState -> state.user.toDto() },
  )
```

The workflow stays visible. The boundary stays typed. The repetitive adapter code disappears.

## Typed Boundary Use Cases

The core boundary abstractions are:

```kotlin
fun interface TypedUseCase<I, E, O> {
  suspend operator fun invoke(input: I): Either<E, O>
}

fun interface ContextualTypedUseCase<I, E, O> : TypedUseCase<I, E, O> {
  suspend operator fun invoke(input: I, context: WorkflowContext): Either<E, O>

  override suspend fun invoke(input: I): Either<E, O> =
    invoke(input, WorkflowContext())
}
```

Use `TypedUseCase` when the caller only needs input. Use `ContextualTypedUseCase` when the caller may need to pass correlation IDs or other initial workflow context.

## State Projection

Use `toStateUseCase(...)` when the public result comes from the final workflow state.

```kotlin
val registerUser: ContextualTypedUseCase<RegisterUserCommand, ApplicationError, UserDto> =
  useCase<RegisterUserInternalCommand> {
    startWith { command -> command.toValidatedState() }
    then(HashPasswordStep(passwordHasher))
    then(PersistUserStep(userRepository, userIdGenerator))
  }.toStateUseCase(
    inputMapper = ::RegisterUserInternalCommand,
    errorMapper = WorkflowError::toApplicationError,
    outputMapper = { state: RegisteredUserState -> state.user.toDto() },
  )
```

If raw workflow errors are acceptable, use the overload without `errorMapper`:

```kotlin
val registerUser: ContextualTypedUseCase<RegisterUserCommand, WorkflowError, UserDto> =
  useCase<RegisterUserInternalCommand> {
    startWith { command -> command.toValidatedState() }
    then(HashPasswordStep(passwordHasher))
    then(PersistUserStep(userRepository, userIdGenerator))
  }.toStateUseCase(
    inputMapper = ::RegisterUserInternalCommand,
    outputMapper = { state: RegisteredUserState -> state.user.toDto() },
  )
```

`errorMapper` is expected to be total and non-throwing. If you want to preserve raw workflow errors, prefer the overload without `errorMapper`.

## Event Projection

Use `toEventUseCase(...)` when the public result is driven by an emitted event.

Event projection follows the same semantics as `executeForEvent(...)`: it returns the last emitted event of the requested type.

```kotlin
val registerUserCreatedEvent: ContextualTypedUseCase<RegisterUserCommand, ApplicationError, UserCreatedEvent> =
  useCase<RegisterUserInternalCommand> {
    startWith { command -> command.toValidatedState() }
    then(HashPasswordStep(passwordHasher))
    then(PersistUserStep(userRepository, userIdGenerator))
  }.toEventUseCase(
    inputMapper = ::RegisterUserInternalCommand,
    errorMapper = WorkflowError::toApplicationError,
    outputMapper = { event: UserCreatedEvent -> event },
  )
```

## Passing Workflow Context

If the boundary needs correlation or request metadata, invoke the contextual overload:

```kotlin
val result =
  registerUser(
    RegisterUserCommand(...),
    WorkflowContext().addData(WorkflowContext.CORRELATION_ID, "corr-123")
  )
```

If you do not supply context, the adapter uses the library's normal empty `WorkflowContext`.

Taken together, this lets `workflow` model not only the internal orchestration of a business flow, but also the typed boundary that real applications need to expose that flow cleanly.
