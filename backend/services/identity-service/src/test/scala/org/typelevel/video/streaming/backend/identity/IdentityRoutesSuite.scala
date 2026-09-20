package org.typelevel.video.streaming.backend.identity

import cats.effect.{IO, Resource}
import org.http4s.headers.{Authorization, `Content-Type`}
import org.http4s.implicits.uri
import org.http4s.{AuthScheme, Credentials, HttpApp, MediaType, Method, Request, Status}
import org.typelevel.ci.CIString
import org.typelevel.video.streaming.backend.identity.api.*
import org.typelevel.video.streaming.backend.identity.domain.*
import org.typelevel.video.streaming.backend.runtime.auth.{
  BearerAuthenticationMiddleware,
  BearerTokenVerifier,
}
import org.typelevel.video.streaming.backend.runtime.context.IOLocalRequestContext
import smithy4s.http4s.SimpleRestJsonBuilder
import smithy4s.json.Json
import weaver.SimpleIOSuite

object IdentityRoutesSuite extends SimpleIOSuite with IdentityFixture:

  ///////////////////////////////////////////////////////////////////////////////
  // preparation
  ///////////////////////////////////////////////////////////////////////////////

  private val service: IdentityService[IO] = new IdentityService[IO]:
    override def register(
        email: Email,
        password: NewPassword,
        displayName: DisplayName,
    ): IO[UserResponse] =
      if email == existingEmail then
        IO.raiseError(ConflictError(ConflictErrorCode.EMAIL_ALREADY_EXISTS))
      else IO.pure(userResponse)

    override def login(email: Email, password: Password): IO[LoginResponse] =
      if email == loginInput.email && password == loginInput.password then IO.pure(loginResponse)
      else IO.raiseError(InvalidCredentialsError(AuthenticationErrorCode.INVALID_CREDENTIALS))

    override def getCurrentUser(): IO[UserResponse] =
      IO.pure(userResponse)

  private val verifier = new BearerTokenVerifier[IO, Unit]:
    override def verify(token: String): IO[Option[Unit]] =
      IO.pure(Option.when(token == loginResponse.accessToken.value)(()))

  private val routes: Resource[IO, HttpApp[IO]] =
    Resource.eval(IOLocalRequestContext.create[Unit]).flatMap { context =>
      SimpleRestJsonBuilder
        .routes(service)
        .middleware(new BearerAuthenticationMiddleware(verifier, context))
        .resource
        .map(_.orNotFound)
    }

  private def registration(email: Email = registerInput.email): Request[IO] =
    Request[IO](method = Method.POST, uri = uri"/users")
      .withEntity(Json.writeBlob(registerInput.copy(email = email)).toUTF8String)
      .putHeaders(`Content-Type`(MediaType.application.json))

  private def login(input: LoginInput = loginInput): Request[IO] =
    Request[IO](method = Method.POST, uri = uri"/auth/login")
      .withEntity(Json.writeBlob(input).toUTF8String)
      .putHeaders(`Content-Type`(MediaType.application.json))

  private val currentUser: Request[IO] =
    Request[IO](method = Method.GET, uri = uri"/users/me")

  ///////////////////////////////////////////////////////////////////////////////
  // tests
  ///////////////////////////////////////////////////////////////////////////////

  test("POST /users is public, decodes registration, and returns its modeled 201 response") {
    routes.use { app =>
      for
        response <- app(registration())
        actual   <- response.as[UserResponse]
      yield expect.all(
        response.status == Status.Created,
        response.contentType.map(_.mediaType).contains(MediaType.application.json),
        actual == userResponse,
      )
    }
  }

  test("POST /users renders duplicate-email failures as modeled 409 responses") {
    routes.use { app =>
      for
        response <- app(registration(existingEmail))
        actual   <- response.as[ConflictError]
      yield expect.all(
        response.status == Status.Conflict,
        response.contentType.map(_.mediaType).contains(MediaType.application.json),
        actual == ConflictError(ConflictErrorCode.EMAIL_ALREADY_EXISTS),
      )
    }
  }

  test("POST /auth/login is public and returns its modeled 200 response") {
    routes.use { app =>
      for
        response <- app(login())
        actual   <- response.as[LoginResponse]
      yield expect.all(
        response.status == Status.Ok,
        response.contentType.map(_.mediaType).contains(MediaType.application.json),
        actual == loginResponse,
      )
    }
  }

  test("POST /auth/login renders a wrong password as a modeled 401 response") {
    routes.use { app =>
      for
        response <- app(login(loginInput.copy(password = valid(Password("wrong-password")))))
        actual   <- response.as[InvalidCredentialsError]
      yield expect.all(
        response.status == Status.Unauthorized,
        response.contentType.map(_.mediaType).contains(MediaType.application.json),
        actual == InvalidCredentialsError(AuthenticationErrorCode.INVALID_CREDENTIALS),
      )
    }
  }

  test("POST /auth/login renders an unknown user as the same modeled 401 response") {
    routes.use { app =>
      for
        response <- app(login(loginInput.copy(email = valid(Email("missing@example.com")))))
        actual   <- response.as[InvalidCredentialsError]
      yield expect.all(
        response.status == Status.Unauthorized,
        response.contentType.map(_.mediaType).contains(MediaType.application.json),
        actual == InvalidCredentialsError(AuthenticationErrorCode.INVALID_CREDENTIALS),
      )
    }
  }

  test("GET /users/me returns the current user with a valid bearer token") {
    routes.use { app =>
      for
        response <-
          app(
            currentUser.putHeaders(
              Authorization(Credentials.Token(AuthScheme.Bearer, loginResponse.accessToken.value)),
            ),
          )
        actual <- response.as[UserResponse]
      yield expect.all(
        response.status == Status.Ok,
        response.contentType.map(_.mediaType).contains(MediaType.application.json),
        actual == userResponse,
      )
    }
  }

  test("GET /users/me rejects a missing bearer token with 401") {
    routes.use { app =>
      app(currentUser).map { response =>
        expect.all(
          response.status == Status.Unauthorized,
          response.headers.get(CIString("WWW-Authenticate")).exists(_.head.value == "Bearer"),
        )
      }
    }
  }

  test("GET /users/me rejects an invalid bearer token with 401") {
    routes.use { app =>
      app(
        currentUser.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, "invalid-token"))),
      ).map { response =>
        expect.all(
          response.status == Status.Unauthorized,
          response.headers.get(CIString("WWW-Authenticate")).exists(_.head.value == "Bearer"),
        )
      }
    }
  }
