# Blocking and Java callers

> Compiled: the code lives in
> [`BlockingCallersRecipeTest.kt`](../../src/test/kotlin/io/liquidsoftware/workflow/recipes/BlockingCallersRecipeTest.kt)
> and runs with the build.

A `UseCase` is a `suspend` function that returns an Arrow `Either`. Ktor handlers and
Spring controllers can call it directly (see [Ktor](ktor.md) and [Spring](spring.md)).
Everything else, such as a message listener, a scheduled job, a CLI, or Java code, needs a
bridge.

## Blocking Kotlin code

Use `runBlocking` at the edge, once per request:

```kotlin
fun placeOrderBlocking(command: PlaceOrder, requestId: String): Either<ApiError, OrderDto> =
  runBlocking { placeOrder(command, WorkflowContext.of(WorkflowContext.CORRELATION_ID to requestId)) }
```

The calling thread waits while the use case runs; its steps, parallel branches and
launched work still run concurrently inside it. Two rules:

- Call it only from threads that are allowed to block: a servlet worker, a JMS or Kafka
  listener thread, `main`. Never from inside another coroutine or an event loop.
- Pass the correlation ID in the context. The use case puts it in the SLF4J MDC for the
  duration of the call, so the log lines of its steps carry it.

## Java

Java can't call `suspend` functions, and `Either` is clumsy there. Give Java code a small
Kotlin facade that returns a `CompletableFuture` and turns the mapped error into an
exception:

```kotlin
class ApiException(val error: ApiError) : RuntimeException(error.code)

/** A Java-friendly face for the use case: a CompletableFuture that fails with ApiException. */
class OrderApi(private val useCase: UseCase<PlaceOrder, ApiError, OrderDto>) : AutoCloseable {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  fun placeOrder(command: PlaceOrder): CompletableFuture<OrderDto> =
    scope.future { useCase(command).getOrElse { throw ApiException(it) } }

  override fun close() = scope.cancel()
}
```

```text
// Java
OrderDto dto = orderApi.placeOrder(command).join();
```

`future { }` comes with `kotlinx-coroutines-core` on the JVM, which the library already
depends on. Cancelling the returned future cancels the use case. Closing the facade
cancels every call still running, so tie it to your application's lifecycle (a Spring
bean's `close` method is called on shutdown).

Prefer a facade per bounded context over one per use case: it keeps the Java surface
small, and the use cases stay idiomatic Kotlin behind it.
