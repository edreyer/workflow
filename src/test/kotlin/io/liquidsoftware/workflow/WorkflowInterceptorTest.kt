package io.liquidsoftware.workflow

import arrow.core.Either
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class WorkflowInterceptorTest {

  class RecordingInterceptor : WorkflowInterceptor {
    val pre = mutableListOf<String>()
    val post = mutableListOf<String>()
    override suspend fun preExecute(execution: WorkflowExecution, context: WorkflowContext) {
      pre.add(execution.workflowId)
    }
    override suspend fun postExecute(execution: WorkflowExecution, context: WorkflowContext) {
      post.add(execution.workflowId)
    }
  }

  class InterceptedWorkflow(override val id: String) : Workflow<TestCommand, TestEvent>() {
    override suspend fun executeWorkflow(input: TestCommand): Either<WorkflowError, WorkflowResult> {
      val event = TestEvent(input.id, Instant.now())
      return Either.Right(WorkflowResult(listOf(event), WorkflowContext()))
    }
  }

  @Test
  fun `interceptors should be invoked around workflow execution`() {
    val interceptor = RecordingInterceptor()
    val wf1 = InterceptedWorkflow("A")
    val wf2 = InterceptedWorkflow("B")

    val useCase: UseCase<TestUseCaseCommand> = useCase {
      interceptor(interceptor)
      first(wf1)
      then(wf2, mapOf("id" to "id"))
      build()
    }

    val cmd = TestUseCaseCommand(UUID.randomUUID())
    runBlocking { useCase.execute(cmd) }

    assertEquals(listOf("A", "B"), interceptor.pre)
    assertEquals(listOf("A", "B"), interceptor.post)
  }
}
