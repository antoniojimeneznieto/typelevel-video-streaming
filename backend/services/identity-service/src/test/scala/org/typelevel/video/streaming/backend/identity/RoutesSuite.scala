package org.typelevel.video.streaming.backend.identity

import java.util.UUID

import cats.effect.{IO, Ref, Resource}
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.uri
import org.http4s.{HttpApp, MediaType, Method, Request, Status}
import org.typelevel.video.streaming.backend.identity.api.*
import org.typelevel.video.streaming.backend.identity.domain.*
import org.typelevel.video.streaming.backend.runtime.auth.{
  BearerAuthenticationMiddleware,
  BearerTokenVerifier
}
import org.typelevel.video.streaming.backend.runtime.context.IOLocalRequestContext
import smithy4s.Blob
import smithy4s.http4s.SimpleRestJsonBuilder
import smithy4s.json.Json
import weaver.SimpleIOSuite

object RoutesSuite extends SimpleIOSuite:

  private val email       = valid(Email("alice@example.com"))
  private val password    = valid(NewPassword("very-secret-password"))
  private val displayName = valid(DisplayName("Alice"))
  private val input       = RegisterInput(email, password, displayName)
  private val user        = UserResponse(
    UserId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000")),
    email,
    displayName,
    Role.STUDENT
  )

  test("POST /users is public, decodes registration, and returns its modeled 201 response") {
    for
      received <- Ref.of[IO, Option[RegisterInput]](None)
      result   <- routes(value => received.set(Some(value)).as(user)).use { app =>
                  for
                    response <- app(registration)
                    body     <- response.as[String]
                    actual   <- received.get
                  yield expect.all(
                    response.status == Status.Created,
                    Json.read[UserResponse](Blob(body)) == Right(user),
                    actual.contains(input)
                  )
                }
    yield result
  }

  test("POST /users renders duplicate-email failures as modeled 409 responses") {
    val conflict = ConflictError(ConflictErrorCode.EMAIL_ALREADY_EXISTS)

    routes(_ => IO.raiseError(conflict)).use { app =>
      app(registration).flatMap { response =>
        response.as[String].map { body =>
          expect.all(
            response.status == Status.Conflict,
            Json.read[ConflictError](Blob(body)) == Right(conflict)
          )
        }
      }
    }
  }

  private def routes(registerF: RegisterInput => IO[UserResponse]): Resource[IO, HttpApp[IO]] =
    Resource.eval(IOLocalRequestContext.create[Unit]).flatMap { context =>
      val service = new IdentityService[IO]:
        override def register(
            email: Email,
            password: NewPassword,
            displayName: DisplayName
        ): IO[UserResponse] =
          registerF(RegisterInput(email, password, displayName))
        override def login(email: Email, password: Password): IO[LoginResponse] =
          IO.raiseError(new AssertionError("Unexpected login"))
        override def getCurrentUser(): IO[UserResponse] =
          IO.raiseError(new AssertionError("Unexpected current-user request"))
      val verifier = new BearerTokenVerifier[IO, Unit]:
        override def verify(token: String): IO[Option[Unit]] =
          IO.raiseError(new AssertionError("Registration must not authenticate"))

      SimpleRestJsonBuilder
        .routes(service)
        .middleware(new BearerAuthenticationMiddleware(verifier, context))
        .resource
        .map(_.orNotFound)
    }

  private def registration: Request[IO] =
    Request[IO](method = Method.POST, uri = uri"/users")
      .withEntity(Json.writeBlob(input).toUTF8String)
      .putHeaders(`Content-Type`(MediaType.application.json))

  private def valid[A](value: Either[String, A]): A =
    value.fold(message => throw new AssertionError(message), identity)
