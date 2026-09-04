package org.typelevel.video.streaming.backend.common.config

import cats.syntax.all.*
import ciris.*

final case class PostgresConfig(
    host: String,
    port: Int,
    user: String,
    database: String,
    password: String,
    maxConnections: Int,
)

object PostgresConfig {

  def fromEnv(database: String, port: Int): ConfigValue[Effect, PostgresConfig] =
    (
      env("POSTGRES_HOST").as[String].default("localhost"),
      env("POSTGRES_PORT").as[Int].default(port),
      env("POSTGRES_USER").as[String].default(database),
      env("POSTGRES_DB").as[String].default(database),
      env("POSTGRES_PASSWORD").as[String].default(database),
      env("POSTGRES_MAX_CONNECTIONS").as[Int].default(10),
    ).parMapN(PostgresConfig.apply)

}
