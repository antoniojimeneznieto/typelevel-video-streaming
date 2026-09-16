package org.typelevel.video.streaming.backend.catalog.config

import cats.effect.Async
import cats.syntax.all.*
import com.comcast.ip4s.port
import org.typelevel.video.streaming.backend.runtime.config.{HttpServerConfig, PostgresConfig}

final case class AppConfig(
    server: HttpServerConfig,
    postgres: PostgresConfig,
)

object AppConfig:

  def load[F[_]: Async]: F[AppConfig] =
    (
      HttpServerConfig.config(port"8082"),
      PostgresConfig.config(
        defaultDatabase = "catalog",
        defaultPassword = "catalog-local-secret",
      ),
    ).parMapN(AppConfig.apply).load[F]
