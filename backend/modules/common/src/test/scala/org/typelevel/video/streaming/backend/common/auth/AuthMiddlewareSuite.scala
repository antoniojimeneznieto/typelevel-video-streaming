package org.typelevel.video.streaming.backend.common.auth

import cats.data.OptionT
import cats.effect.IO
import org.http4s.headers.{Authorization, `WWW-Authenticate`}
import org.http4s.implicits.*
import org.http4s.{AuthScheme, Credentials, HttpRoutes, Method, Request, Response, Status}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import weaver.SimpleIOSuite

object AuthMiddlewareSuite extends SimpleIOSuite {

  private given Logger[IO] = NoOpLogger[IO]

  private val caller: Caller =
    Caller(
      subject = "sub-123",
      email = Some("ada@example.com"),
      preferredUsername = "ada",
      emailVerified = true,
      roles = Set(Role.User),
    )

  private def verifierReturning(result: Either[AuthError, Caller]): TokenVerifier =
    new TokenVerifier {
      def verify(token: String): IO[Either[AuthError, Caller]] = IO.pure(result)
    }

  private def echoSubject(context: CallerContext): HttpRoutes[IO] =
    HttpRoutes[IO] { _ =>
      OptionT.liftF(context.require.map(c => Response[IO](Status.Ok).withEntity(c.subject)))
    }

  private def run(verifier: TokenVerifier, request: Request[IO]): IO[Response[IO]] =
    CallerContext.make.flatMap { context =>
      val middleware = AuthMiddleware(verifier, context)
      middleware.apply(echoSubject(context)).orNotFound.run(request)
    }

  private val withBearer: Request[IO] =
    Request[IO](Method.GET, uri"/api/account/profile")
      .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, "a.token")))

  private val withoutBearer: Request[IO] =
    Request[IO](Method.GET, uri"/api/account/profile")

  test("a valid token sets the caller in scope and reaches the route") {
    run(verifierReturning(Right(caller)), withBearer).flatMap { response =>
      response.as[String].map { body =>
        expect(response.status == Status.Ok) and expect(body == caller.subject)
      }
    }
  }

  test("a missing token is rejected with 401 and a Bearer challenge") {
    run(verifierReturning(Right(caller)), withoutBearer).map { response =>
      val challenge = response.headers.get[`WWW-Authenticate`].map(_.values.head.scheme)
      expect(response.status == Status.Unauthorized) and
        expect(challenge.contains("Bearer"))
    }
  }

  test("an invalid token is rejected with 401") {
    run(verifierReturning(Left(AuthError.InvalidToken("expired"))), withBearer).map { response =>
      expect(response.status == Status.Unauthorized)
    }
  }

}
