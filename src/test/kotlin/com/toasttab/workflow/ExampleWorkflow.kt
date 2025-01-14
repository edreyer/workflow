package com.toasttab.workflow

import arrow.core.Either
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking

// Define the commands
class CreateUserCommand(val id: UUID, val name: String) : Command
class CreateUserPreferencesCommand(val userId: UUID, val preferences: Map<String, String>) : Command
class SendWelcomeEmailCommand(val userId: UUID, val email: String) : Command

// Define the events
data class UserCreatedEvent(override val id: UUID, override val timestamp: Instant, val name: String) : Event
data class UserPreferencesCreatedEvent(override val id: UUID, override val timestamp: Instant, val preferences: Map<String, String>) : Event
data class WelcomeEmailSentEvent(override val id: UUID, override val timestamp: Instant, val email: String) : Event

// Define the workflows
class CreateUserWorkflow : Workflow<CreateUserCommand, UserCreatedEvent>() {
    override suspend fun executeWorkflow(input: CreateUserCommand, context: Context): Either<WorkflowError, WorkflowResult> {
        val event = UserCreatedEvent(input.id, Instant.now(), input.name)
        val updatedContext = context.addData("userId", input.id)
        return Either.Right(WorkflowResult(updatedContext, listOf(event)))
    }
}

class CreateUserPreferencesWorkflow : Workflow<CreateUserPreferencesCommand, UserPreferencesCreatedEvent>() {
    override suspend fun executeWorkflow(input: CreateUserPreferencesCommand, context: Context): Either<WorkflowError, WorkflowResult> {
        val event = UserPreferencesCreatedEvent(input.userId, Instant.now(), input.preferences)
        return Either.Right(WorkflowResult(context, listOf(event)))
    }
}

class SendWelcomeEmailWorkflow : Workflow<SendWelcomeEmailCommand, WelcomeEmailSentEvent>() {
    override suspend fun executeWorkflow(input: SendWelcomeEmailCommand, context: Context): Either<WorkflowError, WorkflowResult> {
        val event = WelcomeEmailSentEvent(input.userId, Instant.now(), input.email)
        return Either.Right(WorkflowResult(context, listOf(event)))
    }
}

fun main() {
    val createUserWorkflow = CreateUserWorkflow()
    val createUserPreferencesWorkflow = CreateUserPreferencesWorkflow()
    val sendWelcomeEmailWorkflow = SendWelcomeEmailWorkflow()

    val useCase = useCase(createUserWorkflow) {
        runParallel(true)
        thenIf(createUserPreferencesWorkflow, { result -> result.events.isNotEmpty() }) { result ->
            val userId = result.context.getData("userId") as UUID
            CreateUserPreferencesCommand(userId, mapOf("theme" to "dark", "notifications" to "enabled"))
        }
        thenIf(sendWelcomeEmailWorkflow, { result -> result.events.isNotEmpty() }) { result ->
            val userId = result.context.getData("userId") as UUID
            SendWelcomeEmailCommand(userId, "user@example.com")
        }
        build()
    }

    val input = CreateUserCommand(UUID.randomUUID(), "John Doe")

    val result = runBlocking { useCase.execute(input) }

    when (result) {
        is Either.Right -> {
            println("Workflow executed successfully with events: ${result.value.events}")
            println("Final context data: ${result.value.context.data}")
        }
        is Either.Left -> {
            println("Workflow execution failed with error: ${result.value}")
        }
    }
}
