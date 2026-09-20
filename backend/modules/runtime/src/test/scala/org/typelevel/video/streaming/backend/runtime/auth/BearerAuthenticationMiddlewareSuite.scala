package org.typelevel.video.streaming.backend.runtime.auth

import cats.effect.IO
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Header, HttpApp, Request, Response, Status}
import org.typelevel.ci.CIString
import org.typelevel.video.streaming.backend.runtime.context.IOLocalRequestContext
import smithy.api.{Auth, HttpBearerAuth}
import smithy4s.Hints
import weaver.SimpleIOSuite

object BearerAuthenticationMiddlewareSuite extends SimpleIOSuite:

  ///////////////////////////////////////////////////////////////////////////////
  // preparation
  ///////////////////////////////////////////////////////////////////////////////

  private val serviceHints           = Hints(HttpBearerAuth())
  private val protectedEndpointHints = Hints.empty
  private val publicEndpointHints    = Hints(Auth(Set.empty))

  private def createMiddleware(
      context: IOLocalRequestContext[String],
  ): BearerAuthenticationMiddleware[IO, String] =
    val verifier = new BearerTokenVerifier[IO, String]:
      override def verify(token: String): IO[Option[String]] =
        IO.pure(Option.when(token == "valid")("alice"))

    BearerAuthenticationMiddleware(verifier, context)

  private val okApp: HttpApp[IO] =
    HttpApp[IO](_ => IO.pure(Response[IO](Status.Ok)))

  private def requestWithToken(token: String): Request[IO] =
    Request[IO]().putHeaders(
      Authorization(Credentials.Token(AuthScheme.Bearer, token)),
    )

  ///////////////////////////////////////////////////////////////////////////////
  // tests
  ///////////////////////////////////////////////////////////////////////////////

  test("a public endpoint does not require a token") {
    for
      context   <- IOLocalRequestContext.create[String]
      middleware = createMiddleware(context)
      app        = middleware.prepareWithHints(serviceHints, publicEndpointHints)(okApp)
      response  <- app(requestWithToken("invalid"))
    yield expect(response.status == Status.Ok)
  }

  test("a protected endpoint rejects a missing token") {
    for
      context   <- IOLocalRequestContext.create[String]
      middleware = createMiddleware(context)
      app        = middleware.prepareWithHints(serviceHints, protectedEndpointHints)(okApp)
      response  <- app(Request[IO]())
    yield expect(response.status == Status.Unauthorized) and
      expect(
        response.headers
          .get(CIString("WWW-Authenticate"))
          .exists(_.head.value == "Bearer"),
      )
  }

  test("a protected endpoint rejects an invalid token") {
    for
      context   <- IOLocalRequestContext.create[String]
      middleware = createMiddleware(context)
      app        = middleware.prepareWithHints(serviceHints, protectedEndpointHints)(okApp)
      response  <- app(requestWithToken("invalid"))
    yield expect(response.status == Status.Unauthorized)
  }

  test("a verified principal is scoped to the protected endpoint") {
    for
      context   <- IOLocalRequestContext.create[String]
      middleware = createMiddleware(context)
      endpoint   = HttpApp[IO] { _ =>
                   context.get.map { principal =>
                     Response[IO](Status.Ok).putHeaders(
                       Header.Raw(CIString("X-Test-Principal"), principal.getOrElse("missing")),
                     )
                   }
                 }
      app       = middleware.prepareWithHints(serviceHints, protectedEndpointHints)(endpoint)
      response <- app(requestWithToken("valid"))
      outside  <- context.get
    yield expect(
      response.headers
        .get(CIString("X-Test-Principal"))
        .exists(_.head.value == "alice"),
    ) and expect(outside.isEmpty)
  }
