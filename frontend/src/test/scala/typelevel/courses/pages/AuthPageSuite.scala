package typelevel.courses.pages

import munit.FunSuite
import org.typelevel.video.streaming.backend.identity.api.{
  AuthenticationErrorCode,
  InvalidCredentialsError,
}

final class AuthPageSuite extends FunSuite:
  test("registration requires a stronger password than login") {
    assertEquals(AuthPage.validate(false, "", "alice@example.com", "short"), None)
    assert(AuthPage.validate(true, "Alice", "alice@example.com", "short").nonEmpty)
    assertEquals(AuthPage.validate(true, "Alice", "alice@example.com", "password1234"), None)
  }

  test("invalid credentials produce a useful login message") {
    assertEquals(
      AuthPage.authErrorMessage(
        InvalidCredentialsError(AuthenticationErrorCode.INVALID_CREDENTIALS),
      ),
      "The email or password is incorrect.",
    )
  }
