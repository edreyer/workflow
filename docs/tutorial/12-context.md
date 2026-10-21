# 12. Request Context

Code: [`Ch12Context.kt`](../../src/test/kotlin/io/liquidsoftware/workflow/tutorial/Ch12Context.kt)

Some data is about the request, not the order: the tenant, the feature flags, the correlation ID for tracing. It doesn't belong in the command, and it shouldn't be threaded through every state type. `WorkflowContext` carries it alongside the use case.

## Keys

A context holds values under typed keys. Keys compare by identity, so declare each one once and share it:

```kotlin
object RequestKeys {
  val TENANT = Key<String>("tenant")
  val FEATURES = Key<Set<String>>("features")
}
```

The name is only for diagnostics. The value type is part of the key, so `context[RequestKeys.FEATURES]` is a `Set<String>?` with no cast.

## Passing A Context

The caller builds the context and passes it with the input:

```kotlin
val context = WorkflowContext.of(
  WorkflowContext.CORRELATION_ID to "req-42",
  RequestKeys.TENANT to "wholesale",
  RequestKeys.FEATURES to setOf("gift-wrap"),
)

placeOrder(order, context)
placeOrder(order) // an empty context
```

`key to value` checks the value type, so `RequestKeys.TENANT to 42` doesn't compile.

## Reading It

Steps, `thenIf` and `branch` predicates, and `result` read `context[key]`:

```kotlin
val placeOrder: UseCase<Order, Failure<Nothing>, OrderConfirmation> =
  useCase("PlaceOrder") {
    startWith { order -> order }
      .then("price") { order ->
        val tenant = Tenant(context[RequestKeys.TENANT] ?: "retail")
        OrderConfirmation(order.id, context(tenant) { priceFor(order) }, giftWrapped = false)
      }
      .thenIf(giftWrap) { "gift-wrap" in context[RequestKeys.FEATURES].orEmpty() }
      .result { it }
  }
```

The context is read-only. A step can't add to it: data a step produces goes in the state it returns, where the compiler can check it.

## Keeping Domain Code Free Of Keys

Domain functions shouldn't know about `Key` lookups. Have them declare what they need as a context parameter, and supply it from the step with Kotlin's `context(value) { }`, as `price` does above:

```kotlin
data class Tenant(val id: String)

context(tenant: Tenant)
fun priceFor(order: Order): Long = if (tenant.id == "wholesale") order.total * 8 / 10 else order.total
```

## The Correlation ID

`WorkflowContext.CORRELATION_ID` is built in. While the use case runs, it is in the SLF4J MDC as `correlationId`, so your own log lines inside steps carry it. Add `%X{correlationId}` to your log pattern to see it. It also appears in each `UseCaseSummary` (next chapter).

Keep context values operational. Don't put domain data or secrets in the context to make them easy to reach.

## What You Learned

- `WorkflowContext` carries typed request metadata under shared `Key`s.
- Steps, predicates, and `result` read it; nothing changes it.
- Supply context values to domain functions as context parameters.
- `CORRELATION_ID` flows into the MDC and the summary.

Next: [Logs, summaries and metrics](13-observability.md).
