package org.typelevel.video.streaming.frontend.config

import cats.effect.IO
import io.circe.Decoder
import io.circe.parser.decode
import org.http4s.dom.FetchClientBuilder
import org.scalajs.dom

final case class AppConfig(
    keycloak: KeycloakConfig,
    userServiceBaseUrl: String
)

object AppConfig:

  def load: IO[AppConfig] =
    val origin = dom.window.location.origin
    val path = dom.window.location.pathname

    fetchConfigText.flatMap { text =>
      IO.fromEither(parseConfig(text, redirectUri = s"$origin$path"))
    }

  private def fetchConfigText: IO[String] =
    FetchClientBuilder[IO]
      .withCache(dom.RequestCache.`no-store`)
      .resource
      .use(_.expect[String]("config.json"))

  private def parseConfig(text: String, redirectUri: String): Either[Throwable, AppConfig] =
    decode[ConfigFile](text).map(_.toAppConfig(redirectUri))

  private final case class ConfigFile(
      keycloak: ConfigFile.Keycloak,
      userServiceBaseUrl: String
  ) derives Decoder:
    def toAppConfig(redirectUri: String): AppConfig =
      AppConfig(
        keycloak = KeycloakConfig(
          baseUrl = keycloak.baseUrl,
          realm = keycloak.realm,
          clientId = keycloak.clientId,
          redirectUri = redirectUri
        ),
        userServiceBaseUrl = userServiceBaseUrl
      )

  private object ConfigFile:

    final case class Keycloak(
        baseUrl: String,
        realm: String,
        clientId: String
    ) derives Decoder
