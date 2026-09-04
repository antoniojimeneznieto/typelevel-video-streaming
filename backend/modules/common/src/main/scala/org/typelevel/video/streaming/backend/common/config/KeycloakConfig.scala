package org.typelevel.video.streaming.backend.common.config

import cats.effect.IO
import cats.syntax.all.*
import ciris.*

final case class KeycloakConfig(url: String, realm: String, issuerUrl: String, audience: String)

object KeycloakConfig {

  private val defaultUrl   = "http://localhost:8082"
  private val defaultRealm = "typelevel-video-streaming"

  def fromEnv(defaultAudience: String): ConfigValue[Effect, KeycloakConfig] =
    (
      env("KEYCLOAK_URL").as[String].default(defaultUrl),
      env("KEYCLOAK_REALM").as[String].default(defaultRealm),
      env("KEYCLOAK_ISSUER_URL").as[String].or(env("KEYCLOAK_URL").as[String]).default(defaultUrl),
      env("OIDC_AUDIENCE").as[String].default(defaultAudience),
    ).parMapN(KeycloakConfig.apply)

  def load(defaultAudience: String): IO[KeycloakConfig] =
    fromEnv(defaultAudience).load[IO]

}
