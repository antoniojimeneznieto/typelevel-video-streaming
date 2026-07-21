package org.typelevel.video.streaming.backend.user.config

import cats.effect.IO
import cats.syntax.all.*
import ciris.*
import com.comcast.ip4s.{Port, port}
import org.typelevel.video.streaming.backend.common.config.{
  KeycloakConfig,
  PostgresConfig,
  ServerConfig
}

final case class AppConfig(
    server: ServerConfig,
    postgres: PostgresConfig,
    keycloak: KeycloakConfig
)

object AppConfig:

  private val defaultServerPort: Port = port"8081"
  private val defaultDbPort: Int = 5432
  private val databaseName: String = "userservice"
  private val audience: String = "user-service"

  val fromEnv: ConfigValue[Effect, AppConfig] =
    (
      ServerConfig.fromEnv(defaultServerPort),
      PostgresConfig.fromEnv(databaseName, defaultDbPort),
      KeycloakConfig.fromEnv(audience)
    ).parMapN(AppConfig.apply)

  def load: IO[AppConfig] = fromEnv.load[IO]
