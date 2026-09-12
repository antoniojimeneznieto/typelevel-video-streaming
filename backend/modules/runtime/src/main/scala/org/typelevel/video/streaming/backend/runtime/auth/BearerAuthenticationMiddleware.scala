package org.typelevel.video.streaming.backend.runtime.auth

import cats.data.Kleisli
import cats.Monad
import cats.syntax.all.*
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Header, HttpApp, Request, Response, Status}
import org.typelevel.ci.CIString
import org.typelevel.video.streaming.backend.runtime.context.RequestContext
import smithy.api.{Auth, AuthTraitReference, HttpBearerAuth}
import smithy4s.Hints
import smithy4s.http4s.ServerEndpointMiddleware

final class BearerAuthenticationMiddleware[F[_]: Monad, Principal](
    verifier: BearerTokenVerifier[F, Principal],
    context: RequestContext[F, Principal]
) extends ServerEndpointMiddleware.Simple[F]:

  override def prepareWithHints(
      serviceHints: Hints,
      endpointHints: Hints
  ): HttpApp[F] => HttpApp[F] =
    if requiresBearerAuthentication(serviceHints, endpointHints) then authenticate
    else identity

  private def authenticate(http: HttpApp[F]): HttpApp[F] =
    Kleisli { request =>
      bearerToken(request) match
        case Some(token) =>
          verifier.verify(token).flatMap {
            case Some(principal) => context.scope(principal)(http(request))
            case None => unauthorized
          }
        case None => unauthorized
    }

  private def bearerToken(request: Request[F]): Option[String] =
    request.headers.get[Authorization].collect {
      case Authorization(Credentials.Token(AuthScheme.Bearer, token)) => token
    }

  private def unauthorized: F[Response[F]] =
    Response[F](Status.Unauthorized)
      .putHeaders(Header.Raw(CIString("WWW-Authenticate"), "Bearer"))
      .pure[F]

  private def requiresBearerAuthentication(
      serviceHints: Hints,
      endpointHints: Hints
  ): Boolean =
    endpointHints.get(Auth) match
      case Some(auth) =>
        Auth.value(auth).exists { reference =>
          AuthTraitReference.value(reference) == HttpBearerAuth.id
        }
      case None => serviceHints.has[HttpBearerAuth]
