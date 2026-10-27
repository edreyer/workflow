# Ktor

> Not compiled: this recipe is checked by reading, not by the build. It targets Ktor 3
> with `ContentNegotiation` (kotlinx.serialization) and the `CallId` plugin.

Ktor handlers are already coroutines, so a route calls a use case directly. The route's
job is the HTTP boundary: decode the request, pass request metadata as context, and turn
the `Either` into a response.

## Building the use cases

Use cases are values built from their dependencies. Build them once in the module and
hand them to the routes:

```kotlin
fun Application.module() {
  val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
  install(MicrometerMetrics) { this.registry = registry }

  val config = appConfig(registry) // see the Micrometer recipe
  val services = Services(OrderRepository(database), PaymentGateway(httpClient))
  orderRoutes(placeOrder(services, config))
}
```

With Koin, register each use case as a `single { placeOrder(get(), get()) }` and
`inject<UseCase<PlaceOrder, ApiError, OrderDto>>()` it in the route; the shape is the
same.

## The route

```kotlin
fun Application.orderRoutes(placeOrder: UseCase<PlaceOrder, ApiError, OrderDto>) {
  install(ContentNegotiation) { json() }
  install(CallId) {
    retrieveFromHeader(HttpHeaders.XRequestId)
    generate { UUID.randomUUID().toString() }
    replyToHeader(HttpHeaders.XRequestId)
  }

  routing {
    post("/orders") {
      val command = call.receive<PlaceOrderRequest>().toCommand()
      call.respondUseCase(placeOrder(command, call.workflowContext()), HttpStatusCode.Created)
    }
  }
}
```

`ApiError` is the application's error type from the [tutorial](../tutorial/02-typed-errors.md),
`data class ApiError(val status: Int, val code: String)`. `PlaceOrderRequest` is a `@Serializable` DTO of plain strings and numbers; `toCommand()`
maps it to the use case's input. Keep library types and domain types out of the wire
format, so decoding never fails half way through a domain rule.

## Two helpers

```kotlin
fun ApplicationCall.workflowContext(): WorkflowContext =
  callId?.let { WorkflowContext.of(WorkflowContext.CORRELATION_ID to it) } ?: WorkflowContext()

@Serializable
data class ErrorBody(val code: String)

suspend inline fun <reified O : Any> ApplicationCall.respondUseCase(
  result: Either<ApiError, O>,
  success: HttpStatusCode = HttpStatusCode.OK,
) = result.fold(
  ifLeft = { error -> respond(HttpStatusCode.fromValue(error.status), ErrorBody(error.code)) },
  ifRight = { output -> respond(success, output) },
)
```

With the correlation ID in the context, the use case puts it in the SLF4J MDC as
`correlationId` while it runs, and in every `UseCaseSummary`. If you also use Ktor's
`CallLogging` with `callIdMdc("correlationId")`, your request logs and the use case's
logs share the key.

## Errors as problem details

If your API answers with RFC 9457 problem details, make the mapper produce them. The
use case's `errors` function is the one place that knows every failure, so the status
codes live there, next to the exhaustive `when`:

```kotlin
@Serializable
data class Problem(val type: String, val title: String, val status: Int, val detail: String? = null)

fun toProblem(failure: Failure<OrderError>): Problem = when (failure) {
  is Failure.Raised -> when (val error = failure.error) {
    OrderError.EmptyCart -> Problem("/problems/empty-cart", "The cart is empty.", 400)
    is OrderError.UnknownProduct -> Problem("/problems/unknown-product", "Unknown product.", 422, error.sku)
    is OrderError.PaymentFailed -> Problem("/problems/payment-failed", "The payment failed.", 402)
  }
  is Failure.Unexpected -> Problem("about:blank", "Internal error.", 500)
}

val problemJson = ContentType.parse("application/problem+json")

suspend fun ApplicationCall.respondProblem(problem: Problem) =
  respondText(Json.encodeToString(problem), problemJson, HttpStatusCode.fromValue(problem.status))
```

Build the use case with `errors = ::toProblem`, and the route folds its result with
`ifLeft = { call.respondProblem(it) }`.

## Things to know

- **Cancellation follows the handler.** The use case, its parallel branches and its
  launched work are children of the handler's coroutine, so they stop when it is
  cancelled. By default Ktor keeps processing a request after the client disconnects;
  to stop work for disconnected clients, install the `HttpRequestLifecycle` plugin with
  `cancelCallOnClose = true` on an engine that supports it. Either way, work that must
  finish regardless belongs in an outbox or a queue, not in `thenLaunch`.
- **Don't block in steps.** Steps run on Ktor's dispatcher. Wrap blocking JDBC or SDK
  calls in `withContext(Dispatchers.IO) { }` inside the step, or in the service the
  step calls.
- **Testing the route** doesn't need the real use case. `UseCase` is a `fun interface`, so
  `testApplication { application { orderRoutes(UseCase { _, _ -> Either.Left(ApiError(409, "duplicate")) }) } }`
  checks the HTTP mapping on its own.
