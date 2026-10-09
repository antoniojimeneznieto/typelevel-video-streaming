package org.typelevel.video.streaming.backend.identity.config

import java.nio.file.{Path, Paths}
import scala.util.Try

import cats.effect.Async
import cats.syntax.all.*
import ciris.{ConfigDecoder, ConfigValue, Effect, env}
import com.comcast.ip4s.port
import org.typelevel.video.streaming.backend.identity.api.ExpiresInSeconds
import org.typelevel.video.streaming.backend.runtime.config.{HttpServerConfig, PostgresConfig}

final case class JwtConfig(
    privateKeyPath: Path,
    publicKeyPath: Path,
    accessTokenExpiresIn: ExpiresInSeconds,
)

final case class AppConfig(
    server: HttpServerConfig,
    postgres: PostgresConfig,
    jwt: JwtConfig,
    workshopSubjectMigration: Boolean,
)

object AppConfig:

  private val defaultTokenLifetime =
    ExpiresInSeconds(7200).fold(message => throw new IllegalStateException(message), identity)

  private given ConfigDecoder[String, Path] =
    ConfigDecoder[String].mapOption("Path")(value => Try(Paths.get(value)).toOption)

  private given ConfigDecoder[String, ExpiresInSeconds] =
    ConfigDecoder[String, Int].mapOption("ExpiresInSeconds") { value =>
      ExpiresInSeconds(value).toOption
    }

  private val jwtConfig: ConfigValue[Effect, JwtConfig] =
    (
      env("JWT_PRIVATE_KEY_PATH")
        .as[Path]
        .default(Paths.get("infrastructure/identity/keys/private-key.pem")),
      env("JWT_PUBLIC_KEY_PATH")
        .as[Path]
        .default(Paths.get("infrastructure/identity/keys/public-key.pem")),
      env("JWT_ACCESS_TOKEN_EXPIRES_IN_SECONDS")
        .as[ExpiresInSeconds]
        .default(defaultTokenLifetime),
    ).parMapN(JwtConfig.apply)

  private val config: ConfigValue[Effect, AppConfig] =
    (
      HttpServerConfig.config(port"8081"),
      PostgresConfig.config(
        defaultDatabase = "identity",
        defaultPassword = "identity-local-secret",
      ),
      jwtConfig,
      env("WORKSHOP_SUBJECT_MIGRATION").as[Boolean].default(false),
    ).parMapN(AppConfig.apply)

  def load[F[_]: Async]: F[AppConfig] =
    config.load[F]
