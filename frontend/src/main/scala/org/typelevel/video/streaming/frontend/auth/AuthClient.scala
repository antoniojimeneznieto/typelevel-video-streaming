package org.typelevel.video.streaming.frontend.auth

import scala.concurrent.duration.*

import cats.effect.IO
import io.circe.Decoder
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.client.Client
import org.http4s.{Method, Request, Uri, UrlForm}
import org.scalajs.dom
import org.typelevel.video.streaming.frontend.config.KeycloakConfig

final class AuthClient(config: KeycloakConfig, client: Client[IO]):

  import AuthClient.*

  private val tokenUri =
    Uri.unsafeFromString(config.tokenEndpoint)

  def completeLoginOrRestore: IO[AuthStatus] =
    queryParam("code") match
      case Some(code) =>
        val requestedState = queryParam("state")
        loadPkce.flatMap {
          case Some(pkce) if requestedState.contains(pkce.state) =>
            exchangeCode(code, pkce.verifier).flatTap(storeSession).flatMap { session =>
              clearPkce *>
                cleanCallbackUrl(pkce.returnTo) *>
                IO.pure(AuthStatus.SignedIn(session))
            }
          case _ =>
            clearPkce.as(
              AuthStatus.Failed("The sign-in response did not match this browser session.")
            )
        }
      case None =>
        currentSession.map {
          case Some(session) => AuthStatus.SignedIn(session)
          case None          => AuthStatus.SignedOut
        }

  def login(returnTo: String): IO[Unit] =
    startAuthorization(returnTo, requiredAction = None)

  def configureTotp(returnTo: String): IO[Unit] =
    startAuthorization(returnTo, requiredAction = Some("CONFIGURE_TOTP"))

  private def startAuthorization(returnTo: String, requiredAction: Option[String]): IO[Unit] =
    for
      now <- nowMillis
      pkce <- Pkce.create(returnTo, now)
      _ <- storePkce(pkce.storedState)
      _ <- IO(
        dom.window.location.assign(
          authorizeUrl(pkce.state, pkce.codeChallenge, requiredAction)
        )
      )
    yield ()

  def logout: IO[Unit] =
    loadSession.flatMap { session =>
      clearSession *>
        (session.flatMap(_.idToken) match
          case Some(idToken) => IO(dom.window.location.assign(logoutUrl(idToken)))
          case None          => IO.unit)
    }

  def currentSession: IO[Option[AuthSession]] =
    nowMillis.flatMap { now =>
      loadSession.flatMap {
        case Some(session) if session.isFresh(now) => IO.pure(Some(session))
        case Some(_)                               => refreshSession
        case None                                  => IO.pure(None)
      }
    }

  def refreshSession: IO[Option[AuthSession]] =
    loadSession.flatMap {
      case Some(session) =>
        session.refreshToken match
          case Some(refreshToken) =>
            refresh(refreshToken).flatTap(storeSession).attempt.flatMap {
              case Right(refreshed) => IO.pure(Some(refreshed))
              case Left(_)          => clearSession.as(None)
            }
          case None => clearSession.as(None)
      case None => IO.pure(None)
    }

  private def authorizeUrl(
      state: String,
      codeChallenge: String,
      requiredAction: Option[String]
  ): String =
    val params =
      List(
        "client_id" -> config.clientId,
        "redirect_uri" -> config.redirectUri,
        "response_type" -> "code",
        "scope" -> "openid profile email",
        "state" -> state,
        "code_challenge_method" -> "S256",
        "code_challenge" -> codeChallenge
      ) ++ requiredAction.map(action => "kc_action" -> action).toList

    withQueryParams(
      config.authorizationEndpoint,
      params
    )

  private def logoutUrl(idToken: String): String =
    withQueryParams(
      config.logoutEndpoint,
      List(
        "id_token_hint" -> idToken,
        "post_logout_redirect_uri" -> config.redirectUri
      )
    )

  private def exchangeCode(code: String, verifier: String): IO[AuthSession] =
    tokenRequest(
      UrlForm(
        "grant_type" -> "authorization_code",
        "client_id" -> config.clientId,
        "redirect_uri" -> config.redirectUri,
        "code" -> code,
        "code_verifier" -> verifier
      )
    )

  private def refresh(refreshToken: String): IO[AuthSession] =
    tokenRequest(
      UrlForm(
        "grant_type" -> "refresh_token",
        "client_id" -> config.clientId,
        "refresh_token" -> refreshToken
      )
    )

  private def tokenRequest(form: UrlForm): IO[AuthSession] =
    val request = Request[IO](Method.POST, tokenUri).withEntity(form)

    client.run(request).use { response =>
      response.as[String].flatMap { text =>
        if response.status.code >= 200 && response.status.code < 300 then
          nowMillis.flatMap { now =>
            IO.fromEither(decode[TokenResponse](text).map(_.toSession(now)))
          }
        else
          IO.raiseError(
            new RuntimeException(s"Keycloak token request failed (${response.status.code}): $text")
          )
      }
    }

  private def cleanCallbackUrl(returnTo: String): IO[Unit] =
    IO(dom.window.history.replaceState(null, "", s"${config.redirectUri}$returnTo"))

  private def queryParam(name: String): Option[String] =
    Option(new dom.URLSearchParams(dom.window.location.search).get(name)).filter(_.nonEmpty)

object AuthClient:

  private val sessionKey = "tl-video-streaming.auth.session"
  private val pkceKey = "tl-video-streaming.auth.pkce"
  private val pkceMaxAge = 1.hour

  private final case class TokenResponse(
      access_token: String,
      refresh_token: Option[String],
      id_token: Option[String],
      expires_in: Option[Double]
  ) derives Decoder:

    def toSession(nowMillis: Double): AuthSession =
      AuthSession(
        accessToken = access_token,
        refreshToken = refresh_token,
        idToken = id_token,
        expiresAtMillis = nowMillis + expires_in.getOrElse(300d) * 1000
      )

  private def nowMillis: IO[Double] =
    IO.realTime.map(_.toMillis.toDouble)

  private def withQueryParams(endpoint: String, params: List[(String, String)]): String =
    Uri
      .unsafeFromString(endpoint)
      .withQueryParams(params.toMap)
      .renderString

  private def loadSession: IO[Option[AuthSession]] =
    IO {
      Option(dom.window.sessionStorage.getItem(sessionKey))
        .flatMap(text => decode[AuthSession](text).toOption)
    }

  private def storeSession(session: AuthSession): IO[Unit] =
    IO {
      dom.window.sessionStorage.setItem(sessionKey, session.asJson.noSpaces)
    }

  private def clearSession: IO[Unit] =
    IO(dom.window.sessionStorage.removeItem(sessionKey))

  private def storePkce(pkce: PkceState): IO[Unit] =
    IO {
      val text = pkce.asJson.noSpaces
      dom.window.sessionStorage.setItem(pkceKey, text)
      dom.window.localStorage.setItem(pkceKey, text)
    }

  private def loadPkce: IO[Option[PkceState]] =
    nowMillis.map { now =>
      val stored =
        Option(dom.window.sessionStorage.getItem(pkceKey))
          .orElse(Option(dom.window.localStorage.getItem(pkceKey)))

      stored.flatMap(text => decode[PkceState](text).toOption).filter(_.isFresh(now, pkceMaxAge))
    }

  private def clearPkce: IO[Unit] =
    IO {
      dom.window.sessionStorage.removeItem(pkceKey)
      dom.window.localStorage.removeItem(pkceKey)
    }
