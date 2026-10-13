package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.ensure
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The README quick example, kept compiling. */
class ReadmeExampleTest {

  class UserRepository {
    private val users = mutableMapOf<String, UUID>()
    fun exists(email: String) = email in users
    fun create(email: String, password: String) = RegisteredUser(UUID.randomUUID().also { users[email] = it }, email)
  }

  data class RegisterUser(val email: String, val password: String)
  data class RegistrationDraft(val email: String, val password: String)
  data class RegisteredUser(val id: UUID, val email: String)
  data class UserDto(val id: UUID, val email: String)
  data class UserRegistered(val userId: UUID) : BaseEvent()

  sealed interface RegistrationError : WorkflowError {
    data object EmailTaken : RegistrationError
  }

  data class ApiError(val status: Int, val code: String)

  fun createUser(users: UserRepository) =
    workflow<RegistrationDraft, RegisteredUser>("create-user") { draft ->
      ensure(!users.exists(draft.email)) { RegistrationError.EmailTaken }
      val user = users.create(draft.email, draft.password)
      emit(UserRegistered(user.id))
      RegisteredUser(user.id, user.email)
    }

  fun registerUser(users: UserRepository): UseCase<RegisterUser, ApiError, UserDto> =
    useCase(
      name = "RegisterUser",
      errors = { error ->
        when (error) {
          RegistrationError.EmailTaken -> ApiError(409, "email-taken")
          is WorkflowError.ValidationError -> ApiError(400, "invalid")
          else -> ApiError(500, "internal")
        }
      },
    ) {
      startWith { command ->
        ensure(command.email.isNotBlank()) { WorkflowError.ValidationError("Email is required") }
        RegistrationDraft(command.email, command.password)
      }
        .then(createUser(users))
        .result { user -> UserDto(user.id, user.email) }
    }

  class RegisterUserUseCase(users: UserRepository) :
    UseCase<RegisterUser, ApiError, UserDto> by ReadmeExampleTest().registerUser(users)

  @Test
  fun `readme example`() = runTest {
    val users = UserRepository()
    val register = registerUser(users)

    assertIs<Either.Right<UserDto>>(register(RegisterUser("person@example.com", "pw")))
    assertEquals(Either.Left(ApiError(409, "email-taken")), register(RegisterUser("person@example.com", "pw")))
    assertEquals(Either.Left(ApiError(400, "invalid")), register(RegisterUser("", "pw")))
  }

  @Test
  fun `use case class delegates to the built use case`() = runTest {
    val register: UseCase<RegisterUser, ApiError, UserDto> = RegisterUserUseCase(UserRepository())
    assertIs<Either.Right<UserDto>>(register(RegisterUser("person@example.com", "pw")))

    val fake = UseCase<RegisterUser, ApiError, UserDto> { _, _ -> Either.Left(ApiError(503, "unavailable")) }
    assertEquals(Either.Left(ApiError(503, "unavailable")), fake(RegisterUser("person@example.com", "pw")))
  }
}
