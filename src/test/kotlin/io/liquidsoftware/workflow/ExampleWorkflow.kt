package io.liquidsoftware.workflow

import arrow.core.Either
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

// Define the commands
class CreateUserCommand(val id: UUID, val name: String) : Command
class CreateUserPreferencesCommand(val userId: UUID, val preferences: Map<String, String>) : Command
class SendWelcomeEmailCommand(val userId: UUID, val email: String) : Command

// Define the events
data class UserCreatedEvent(override val id: UUID, override val timestamp: Instant, val name: String) : Event
data class UserPreferencesCreatedEvent(override val id: UUID, override val timestamp: Instant, val preferences: Map<String, String>) : Event
data class WelcomeEmailSentEvent(override val id: UUID, override val timestamp: Instant, val email: String) : Event

// Define the workflows
class CreateUserWorkflow(override val id: String) : Workflow<CreateUserCommand, UserCreatedEvent>() {
    override suspend fun executeWorkflow(input: CreateUserCommand, context: WorkflowContext): Either<WorkflowError, WorkflowResult> {
        val event = UserCreatedEvent(input.id, Instant.now(), input.name)
        val updatedContext = context.addData("usernameIsDumb", Random.nextBoolean())
        return Either.Right(WorkflowResult(updatedContext, listOf(event)))
    }
}

class CreateUserPreferencesWorkflow(override val id: String) : Workflow<CreateUserPreferencesCommand, UserPreferencesCreatedEvent>() {
    override suspend fun executeWorkflow(input: CreateUserPreferencesCommand, context: WorkflowContext): Either<WorkflowError, WorkflowResult> {
        val event = UserPreferencesCreatedEvent(input.userId, Instant.now(), input.preferences)
        val updatedContext = context.addData("preferencesCompleted", true)
        delay(1000)
        return Either.Right(WorkflowResult(updatedContext, listOf(event)))
    }
}

class SendWelcomeEmailWorkflow(override val id: String) : Workflow<SendWelcomeEmailCommand, WelcomeEmailSentEvent>() {
    override suspend fun executeWorkflow(input: SendWelcomeEmailCommand, context: WorkflowContext): Either<WorkflowError, WorkflowResult> {
        val event = WelcomeEmailSentEvent(input.userId, Instant.now(), input.email)
        delay(1000)
        return Either.Right(WorkflowResult(context, listOf(event)))
    }
}

fun main() {
    val createUserWorkflow = CreateUserWorkflow("CUW1")
    val createUserPreferencesWorkflow = CreateUserPreferencesWorkflow("CUPW1")
    val sendWelcomeEmailWorkflow = SendWelcomeEmailWorkflow("SWEW1")
    val createUserPreferencesWorkflow2 = CreateUserPreferencesWorkflow("CUPW2")
    val sendWelcomeEmailWorkflow2 = SendWelcomeEmailWorkflow("SWEW2")

    val useCase: UseCase<CreateUserCommand> = useCase(createUserWorkflow) {
        runParallel(true)
        then(createUserPreferencesWorkflow) { result ->
            val userId = result.getFieldFromEvent(UserCreatedEvent::id) ?: throw IllegalStateException("User ID not found")
            CreateUserPreferencesCommand(userId, mapOf("theme" to "dark", "notifications" to "enabled"))
        }
        thenIf(sendWelcomeEmailWorkflow, { result -> result.events.isNotEmpty() }) { result ->
            val userId = result.getFieldFromEvent(UserCreatedEvent::id) ?: throw IllegalStateException("User ID not found")
            SendWelcomeEmailCommand(userId, "user@example.com")
        }
        runParallel(false)
        then(createUserPreferencesWorkflow2) { result ->
            val userId = result.getFieldFromEvent(UserCreatedEvent::id) ?: throw IllegalStateException("User ID not found")
            CreateUserPreferencesCommand(userId, mapOf("theme" to "dark", "notifications" to "enabled"))
        }
        thenIf(sendWelcomeEmailWorkflow2, { result -> result.events.isNotEmpty() }) { result ->
            val userId = result.getFieldFromEvent(UserCreatedEvent::id) ?: throw IllegalStateException("User ID not found")
            SendWelcomeEmailCommand(userId, "user@example.com")
        }
        build()
    }

    val input = CreateUserCommand(UUID.randomUUID(), "John Doe")

    val start = System.currentTimeMillis()
    val result = runBlocking { useCase.execute(input) }
    val end = System.currentTimeMillis()

    println("Workflow executed in ${end - start} ms")

    when (result) {
        is Either.Right -> {
            println("Workflow executed successfully with events: ${result.value.events}\n")
            println("Final context data: ${result.value.context.data}\n")
            println("Final executions: ${result.value.context.executions}\n")
        }
        is Either.Left -> {
            println("Workflow execution failed with error: ${result.value}\n")
        }
    }
}
