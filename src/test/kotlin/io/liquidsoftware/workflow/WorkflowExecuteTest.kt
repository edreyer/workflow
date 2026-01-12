package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class WorkflowExecuteTest {

  data class TestState(val id: UUID) : WorkflowState

  data class TestEvent(
    override val id: UUID,
    override val timestamp: Instant
  ) : Event

  class ThrowingWorkflow(override val id: String) : Workflow<TestState, TestState>() {
    override suspend fun executeWorkflow(input: TestState): Either<WorkflowError, WorkflowResult<TestState>> {
      throw RuntimeException("boom")
    }
  }

  class FailingWorkflow(override val id: String) : Workflow<TestState, TestState>() {
    override suspend fun executeWorkflow(input: TestState): Either<WorkflowError, WorkflowResult<TestState>> = either {
      raise(WorkflowError.ExecutionError("failed"))
    }
  }

  @Test
  fun `execute should wrap exception error with execution context`() {
    val workflow = ThrowingWorkflow("throwing")
    val result = runBlocking { workflow.execute(TestState(UUID.randomUUID())) }

    assertTrue(result.isLeft())
    result.fold(
      { error ->
        when (error) {
          is WorkflowError.ExecutionContextError -> {
            assertTrue(error.error is WorkflowError.ExceptionError)
            assertEquals("throwing", error.execution.workflowId)
            assertTrue(!error.execution.succeeded)
            assertTrue(error.execution.endTime >= error.execution.startTime)
          }
          else -> throw AssertionError("Expected ExecutionContextError but got ${error::class.simpleName}")
        }
      },
      { throw AssertionError("Expected Left but got Right: $it") }
    )
  }

  @Test
  fun `execute should wrap left result with execution context`() {
    val workflow = FailingWorkflow("failing")
    val result = runBlocking { workflow.execute(TestState(UUID.randomUUID())) }

    assertTrue(result.isLeft())
    result.fold(
      { error ->
        when (error) {
          is WorkflowError.ExecutionContextError -> {
            assertTrue(error.error is WorkflowError.ExecutionError)
            assertEquals("failing", error.execution.workflowId)
            assertTrue(!error.execution.succeeded)
            assertTrue(error.execution.endTime >= error.execution.startTime)
          }
          else -> throw AssertionError("Expected ExecutionContextError but got ${error::class.simpleName}")
        }
      },
      { throw AssertionError("Expected Left but got Right: $it") }
    )
  }
}
