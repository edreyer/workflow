# Validating input with impossible

> Not compiled: this recipe is checked by reading, not by the build. It is written
> against [impossible](https://github.com/edreyer/impossible) 0.1.0
> (`io.liquidsoftware:impossible`), a library of Kotlin domain types that can't hold
> invalid values, built on the same Arrow `Raise` as this one.

`startWith` turns public input into the first state, and its block is an Arrow `Raise`
for the pipeline's error type. That makes it the place to parse raw input into domain
types: every step after it works with values that are already valid, and the compiler
says so.

## Single values

impossible's factories raise their own typed errors. `withError` adapts them to the
pipeline's error type, the way `mapError` adapts a step:

```kotlin
sealed interface OrderError {
  data class InvalidEmail(val error: Email.Error) : OrderError
  data class InvalidQuantity(val error: PositiveInt.Error) : OrderError
}

startWith { command ->
  val email = withError(OrderError::InvalidEmail) { Email.from(command.email) }
  val quantity = withError(OrderError::InvalidQuantity) { PositiveInt.from(command.quantity) }
  Cart(email, quantity)
}
```

This stops at the first invalid field, like the rest of the pipeline.

## Every invalid field at once

For a form or an API request, report every invalid field together. Build the input
with an impossible composite, whose `validate { }` block accumulates field errors with
their paths, and wrap the whole list in one error case:

```kotlin
data class Signup(val email: Email, val name: NonBlankString, val seats: PositiveInt) {
  companion object : ValueFactory<SignupRequest, NonEmptyList<FieldError<ValidationError>>, Signup> {
    context(raise: Raise<NonEmptyList<FieldError<ValidationError>>>)
    override fun from(value: SignupRequest): Signup = validate {
      val email by Email.fieldAt(value.email)
      val name by NonBlankString.fieldAt(value.name)
      val seats by PositiveInt.fieldAt(value.seats)
      Signup(email, name, seats)
    }
  }
}

sealed interface SignupError {
  data class Invalid(val problems: ProblemDetails) : SignupError
  data object EmailTaken : SignupError
}

val signup: UseCase<SignupRequest, ApiError, AccountDto> =
  useCase("Signup", errors = ::toApiError) {
    startWith { request ->
      withError({ failures: NonEmptyList<FieldError<ValidationError>> -> SignupError.Invalid(failures.toProblemDetails()) }) {
        Signup.from(request)
      }
    }
      .then(createAccount)
      .result { account -> account.toDto() }
  }
```

`toProblemDetails()` turns the failures into RFC 9457 problem details, with a JSON
pointer, a stable code, and a message for each field, so the `errors` mapper can return
them to the client unchanged:

```kotlin
fun toApiError(failure: Failure<SignupError>): ApiError = when (failure) {
  is Failure.Raised -> when (val error = failure.error) {
    is SignupError.Invalid -> ApiError.Problem(error.problems)
    SignupError.EmailTaken -> ApiError.Conflict("email-taken")
  }
  is Failure.Unexpected -> ApiError.Internal
}
```

## Where each check belongs

- **Shape and format** (is this an email, is the quantity positive): the domain types, in
  `startWith`. They hold no I/O and need nothing but the input.
- **Rules that need data** (is the email taken, is the product in stock): steps, which
  get their services as dependencies and raise the pipeline's error type.

Splitting them this way keeps `startWith` pure, and every step's input type documents
which checks have already passed.
