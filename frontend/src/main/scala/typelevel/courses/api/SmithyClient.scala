package typelevel.courses.api

import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import org.http4s.client.Client
import org.http4s.dom.FetchOptions
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials}

final private[api] class SmithyClient[S](
    client: Client[IO],
    build: Client[IO] => Resource[IO, S],
):
  def call[A](
      accessToken: Option[String] = None,
      keepalive: Option[Boolean]  = None,
  )(request: S => IO[A]): IO[A] =
    val transport = Client[IO] { original =>
      val authenticated = accessToken.fold(original)(token =>
        original.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token))),
      )
      val configured = keepalive.fold(authenticated)(enabled =>
        authenticated.withAttribute(FetchOptions.Key, FetchOptions.default.withKeepAlive(enabled)),
      )
      client.run(configured)
    }
    build(transport).use(request).timeout(30.seconds)
