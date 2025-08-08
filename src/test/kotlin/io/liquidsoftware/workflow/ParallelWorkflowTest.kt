package io.liquidsoftware.workflow

import arrow.core.Either
import io.liquidsoftware.workflow.WorkflowError.ExecutionError
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ParallelWorkflowTest {

  @Test
  fun `should limit parallel workflow concurrency`() {
    val active = AtomicInteger(0)
    val maxObserved = AtomicInteger(0)

    val wf1 = ConcurrencyWorkflow("B1", active, maxObserved)
    val wf2 = ConcurrencyWorkflow("B2", active, maxObserved)
    val wf3 = ConcurrencyWorkflow("B3", active, maxObserved)

    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = TestWorkflow("A"))
      parallel(maxConcurrency = 2) {
        then(wf1)
        then(wf2)
        then(wf3)
      }
      build()
    }

    val result = runBlocking { useCase.execute(TestUseCaseCommand(UUID.randomUUID())) }

    assertTrue(result is Either.Right<WorkflowResult>)
    assertEquals(2, maxObserved.get())
  }

  @Test
  fun `should timeout long running parallel workflow`() {
    val useCase: UseCase<TestUseCaseCommand> = useCase {
      first(workflow = TestWorkflow("A"))
      parallel(stepTimeoutMillis = 100) {
        then(DelayedWorkflow("B"))
      }
      build()
    }

    val result = runBlocking { useCase.execute(TestUseCaseCommand(UUID.randomUUID())) }

    assertTrue(result.isLeft())
    result.fold(
      { assertTrue(it is ExecutionError) },
      { fail("Expected Left with ExecutionError but got Right: $it") }
    )
  }
}

class ConcurrencyWorkflow(
  override val id: String,
  private val active: AtomicInteger,
  private val maxObserved: AtomicInteger
) : Workflow<TestCommand, TestEvent>() {
  override suspend fun executeWorkflow(input: TestCommand): Either<WorkflowError, WorkflowResult> {
    val current = active.incrementAndGet()
    maxObserved.updateAndGet { prev -> maxOf(prev, current) }
    delay(200)
    active.decrementAndGet()
    val event = TestEvent(input.id, Instant.now())
    return Either.Right(WorkflowResult(listOf(event), WorkflowContext()))
  }
}
