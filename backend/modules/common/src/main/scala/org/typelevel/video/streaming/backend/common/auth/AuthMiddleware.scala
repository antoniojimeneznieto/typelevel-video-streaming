package org.typelevel.video.streaming.backend.common.auth

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import cats.syntax.all.*
import org.http4s.headers.{Authorization, `Content-Type`, `WWW-Authenticate`}
import org.http4s.{
  AuthScheme,
  Challenge,
  Credentials,
  HttpRoutes,
  MediaType,
  Request,
  Response,
  Status
}
import org.typelevel.log4cats.Logger

final class AuthMiddleware(verifier: TokenVerifier, context: CallerContext)(using
    logger: Logger[IO]
):

  def apply(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    Kleisli { (request: Request[IO]) =>
      extractBearer(request) match
        case None =>
          reject(request, AuthError.MissingToken)
        case Some(token) =>
          OptionT.liftF(verifier.verify(token)).flatMap {
            case Left(error)   => reject(request, error)
            case Right(caller) => OptionT(context.set(caller) *> routes(request).value)
          }
    }

  ///////////////////////////////////////////////////////////////////////////////
  // Helpers
  ///////////////////////////////////////////////////////////////////////////////

  private def extractBearer(request: Request[IO]): Option[String] =
    request.headers.get[Authorization].collect {
      case Authorization(Credentials.Token(AuthScheme.Bearer, token)) => token
    }

  private def reject(request: Request[IO], error: AuthError): OptionT[IO, Response[IO]] =
    OptionT.liftF(
      logger.warn(s"bearer token rejected path=${request.uri.path} reason=${detail(error)}")
    ) *> OptionT.pure[IO](AuthMiddleware.unauthorized)

  private def detail(error: AuthError): String =
    error match
      case AuthError.MissingToken         => "missing bearer token"
      case AuthError.InvalidToken(reason) => reason

object AuthMiddleware:

  private val unauthorized: Response[IO] =
    Response[IO](Status.Unauthorized)
      .putHeaders(`WWW-Authenticate`(Challenge("Bearer", "typelevel-video-streaming")))
      .withEntity("""{"message":"Unauthorized"}""")
      .withContentType(`Content-Type`(MediaType.application.json))
