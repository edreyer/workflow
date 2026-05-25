# Design Guidance

This library is useful when a business operation is more than a single service call. It gives that operation a visible, typed shape:

```text
input -> initial state -> workflow steps -> final state/events -> output
```

## When To Use It

Use `workflow` when a use case has one or more of these properties:

- multiple domain steps that must run in a specific order
- conditional work based on prior results
- independent work that can run in parallel
- emitted domain events
- application-boundary input/output mapping
- a need for typed, explicit failure behavior
- a need to see the whole use case in one place

Do not reach for it when a plain function or one service method is clearer. The point is to reduce orchestration complexity, not to wrap every operation in ceremony.

## Workflows Versus Services

Services and workflows can coexist, but they should carry different responsibilities.

Services provide capabilities:

- persist a user
- send an email
- publish a message
- call an external API
- calculate a price

Workflows represent steps in a business flow:

- validate a registration
- reserve inventory
- process a payment
- prepare a shipment
- notify a customer

A workflow may call services to do the technical work. A workflow should not call other workflows directly. Compose workflows in the use case DSL instead.

## Designing Workflow State

Prefer explicit state types for meaningful transitions:

```kotlin
data class OrderDraft(...) : WorkflowState
data class ValidatedOrder(...) : WorkflowState
data class PricedOrder(...) : WorkflowState
data class PaidOrder(...) : WorkflowState
```

This makes the flow easier to read and gives the compiler more useful boundaries. Avoid using one large mutable state object that every step edits in place.

Good workflow state is:

- specific to the stage of the use case
- immutable
- free of framework types
- named in domain language
- large enough to describe the transition, but not a dumping ground

## Designing Workflow Steps

Keep workflow steps focused. A good step has one reason to change and a clear state transition:

```text
RegistrationDraft -> ValidRegistration
ValidRegistration -> RegisteredUser
OrderDraft -> PricedOrder
```

If a workflow name contains "and", it may be doing too much. Split it unless the two actions are truly one domain operation.

## Application Boundaries

Use `useCase` at application/module boundaries. It keeps boundary concerns in one place:

- public input type
- initial state mapping
- workflow composition
- error mapping
- public output projection

```kotlin
val registerUser: UseCase<RegisterUserCommand, ApplicationError, UserDto> =
  useCase(name = "RegisterUser", errors = WorkflowError::toApplicationError) {
    startWith { command -> command.toInitialState() }
    then(ValidateRegistration())
    then(CreateUser(users))
    result(state<RegisteredUser>()) { state -> state.toDto() }
  }
```

Use `workflowUseCase` only for lower-level code that needs workflow-native state, events, and context rather than a boundary output.

## Parallel Work

Use `parallelJoin` for independent state-producing workflows:

```kotlin
parallelJoin(
  CalculateSubtotal(),
  CalculateTax(),
) { subtotal, tax ->
  PricedOrder(
    orderId = subtotal.orderId,
    subtotal = subtotal.amount,
    tax = tax.amount,
  )
}
```

Use `parallel { ... }` when the workflows are side effects and the current state should continue unchanged:

```kotlin
parallel<PaidOrder> {
  then(SendReceiptEmail())
  then(PublishPaymentEvent())
}
```

Use `thenLaunch` only when the side effect does not need to complete before the main chain continues. Add `awaitLaunched()` before the end of the use case if later steps need launched events/context.

## Events

Events are useful for domain facts emitted during execution, not as the primary way to move the workflow forward. The main chain should usually advance through typed state.

Use event projection when the public result should be a domain event:

```kotlin
result(event<UserRegisteredEvent>()) { event -> event }
```

Otherwise, prefer state projection:

```kotlin
result(state<RegisteredUser>()) { state -> state.toDto() }
```

## Error Boundaries

Keep `WorkflowError` inside the workflow layer when possible. At the application boundary, map it to the error type your caller expects:

```kotlin
useCase(
  name = "RegisterUser",
  errors = { error -> error.toApplicationError() },
) {
  // ...
}
```

The mapper should be total and non-throwing. If you want raw workflow errors, omit `errors`.

## Testing

Test at two levels:

- Workflow tests should verify one state transition and its emitted events/context.
- Use case tests should verify composition, branching, projection, and boundary error mapping.

Useful edge cases:

- invalid `startWith` input
- workflow failure short-circuiting
- conditional branch skipped
- conditional branch executed
- missing projected state or event
- projection mapper throws
- error mapper behavior
- context passed through the boundary

## Naming

Use stable operational names:

```kotlin
useCase(name = "RegisterUser") { ... }
```

Names are used in debug logs and projection errors. Do not include user data, request data, IDs, emails, filenames, or other sensitive values.
