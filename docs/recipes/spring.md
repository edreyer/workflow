# Spring Boot

> Not compiled: this recipe is checked by reading, not by the build. It targets Spring
> Boot 3 or 4 (Spring Framework 6 or 7) with Spring MVC or WebFlux, Jackson, and
> `kotlinx-coroutines-reactor` on the classpath for `suspend` controller methods.

The library has no Spring integration and needs none. A use case is a value, so it is a
bean like any other, and a `suspend` controller method calls it.

## Use cases as beans

Declare the shared `UseCaseConfig` once, and each use case from its dependencies:

```kotlin
@Configuration
class UseCaseBeans {
  @Bean
  fun useCaseConfig(registry: MeterRegistry): UseCaseConfig = appConfig(registry) // see the Micrometer recipe

  @Bean
  fun placeOrderUseCase(orders: OrderRepository, payments: PaymentGateway, config: UseCaseConfig): UseCase<PlaceOrder, ApiError, OrderDto> =
    placeOrder(Services(orders, payments), config)
}
```

Spring matches generic types when it injects, so a constructor parameter of type
`UseCase<PlaceOrder, ApiError, OrderDto>` finds this bean and no other. If you prefer
component scanning, or want a name to put in a stack trace, delegate a class to the
built use case:

```kotlin
@Component
class PlaceOrderUseCase(services: Services, config: UseCaseConfig) :
  UseCase<PlaceOrder, ApiError, OrderDto> by placeOrder(services, config)
```

## The controller

```kotlin
@RestController
class OrderController(private val placeOrder: UseCase<PlaceOrder, ApiError, OrderDto>) {

  @PostMapping("/orders")
  suspend fun post(
    @RequestBody request: PlaceOrderRequest,
    @RequestHeader("X-Request-Id", required = false) requestId: String?,
  ): ResponseEntity<Any> =
    placeOrder(request.toCommand(), workflowContext(requestId)).fold(
      ifLeft = { error -> problem(error) },
      ifRight = { dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto) },
    )
}

fun workflowContext(requestId: String?): WorkflowContext =
  requestId?.let { WorkflowContext.of(WorkflowContext.CORRELATION_ID to it) } ?: WorkflowContext()

fun problem(error: ApiError): ResponseEntity<Any> {
  val status = HttpStatusCode.valueOf(error.status)
  val body = ProblemDetail.forStatusAndDetail(status, error.code)
  return ResponseEntity.status(status).body(body)
}
```

`ApiError` is the application's error type from the
[tutorial](../tutorial/02-typed-errors.md), `data class ApiError(val status: Int, val code: String)`,
produced by the use case's exhaustive `errors` mapper. Spring renders a `ProblemDetail`
body as `application/problem+json`.

Map the error in the controller rather than throwing it to a `@ControllerAdvice`. The
`Either` already says every way the call can fail; an exception would hide that again.
Keep a `@ControllerAdvice` for what the use case can't see, such as a request body that
doesn't parse.

Without `kotlinx-coroutines-reactor`, Spring MVC can't call `suspend` methods. Add it, or
use a blocking method with `runBlocking`, as in [Blocking and Java callers](blocking-callers.md).

## Things to know

- **Blocking calls.** JDBC repositories and most SDK clients block. Wrap those calls in
  `withContext(Dispatchers.IO) { }`, in the step or in the service it calls, so they
  don't hold a thread that other coroutines need.
- **Transactions.** `@Transactional` on a `suspend` function only works with a reactive
  transaction manager. Keep a transaction inside one step; see [Transactions](transactions.md).
- **Domain events.** To hand events to `ApplicationEventPublisher`, wrap it in the
  `EventBus` from [Publishing domain events](domain-events.md).
- **Testing the controller** doesn't need the real use case. In a `@WebMvcTest`, provide a
  `@Bean` of type `UseCase<PlaceOrder, ApiError, OrderDto>` from a `@TestConfiguration`:
  `UseCase { _, _ -> Either.Left(ApiError(409, "duplicate")) }`.
