package io.liquidsoftware.workflow.recipes.blocking

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.ensure
import io.liquidsoftware.workflow.Failure
import io.liquidsoftware.workflow.UseCase
import io.liquidsoftware.workflow.WorkflowContext
import io.liquidsoftware.workflow.useCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

// Recipe: docs/recipes/blocking-callers.md

data class PlaceOrder(val orderId: String, val total: Long)
data class OrderDto(val orderId: String)
data class ApiError(val status: Int, val code: String)

sealed interface OrderError {
  data object EmptyOrder : OrderError
}

fun toApiError(failure: Failure<OrderError>): ApiError = when (failure) {
  is Failure.Raised -> when (failure.error) {
    OrderError.EmptyOrder -> ApiError(400, "empty-order")
  }
  is Failure.Unexpected -> ApiError(500, "internal")
}

val placeOrder: UseCase<PlaceOrder, ApiError, OrderDto> =
  useCase("PlaceOrder", errors = ::toApiError) {
    startWith { command ->
      ensure(command.total > 0) { OrderError.EmptyOrder }
      command
    }
      .result { command -> OrderDto(command.orderId) }
  }

// ---- Blocking Kotlin callers ----------------------------------------------------------------

fun placeOrderBlocking(command: PlaceOrder, requestId: String): Either<ApiError, OrderDto> =
  runBlocking { placeOrder(command, WorkflowContext.of(WorkflowContext.CORRELATION_ID to requestId)) }

// ---- Java callers ---------------------------------------------------------------------------

class ApiException(val error: ApiError) : RuntimeException(error.code)

/** A Java-friendly face for the use case: a CompletableFuture that fails with ApiException. */
class OrderApi(private val useCase: UseCase<PlaceOrder, ApiError, OrderDto>) : AutoCloseable {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  fun placeOrder(command: PlaceOrder): CompletableFuture<OrderDto> =
    scope.future { useCase(command).getOrElse { throw ApiException(it) } }

  override fun close() = scope.cancel()
}

class BlockingCallersRecipeTest {
  @Test
  fun `a blocking caller gets the Either back`() {
    assertEquals(Either.Right(OrderDto("o-1")), placeOrderBlocking(PlaceOrder("o-1", 100), "req-1"))
  }

  @Test
  fun `a Java caller gets a future that completes or fails with the mapped error`() {
    OrderApi(placeOrder).use { api ->
      assertEquals(OrderDto("o-1"), api.placeOrder(PlaceOrder("o-1", 100)).get())

      val failed = assertFailsWith<ExecutionException> { api.placeOrder(PlaceOrder("o-2", 0)).get() }
      assertIs<ApiException>(failed.cause)
      assertEquals(ApiError(400, "empty-order"), (failed.cause as ApiException).error)
    }
  }
}
