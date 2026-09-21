package org.typelevel.video.streaming.backend.runtime.config

import cats.syntax.all.*
import ciris.{ConfigDecoder, ConfigValue, Effect, Secret, env}
import com.comcast.ip4s.{Host, Port, host, port}

final case class PostgresConfig(
    host: Host,
    port: Port,
    user: String,
    database: String,
    password: Secret[String],
    maxConnections: Int,
)

object PostgresConfig:

  private given ConfigDecoder[String, Host] =
    ConfigDecoder[String].mapOption("Host")(Host.fromString)

  private given ConfigDecoder[String, Port] =
    ConfigDecoder[String].mapOption("Port")(Port.fromString)

  private val positiveIntDecoder: ConfigDecoder[String, Int] =
    ConfigDecoder[String, Int].mapOption("PositiveInt") { value =>
      Option.when(value > 0)(value)
    }

  def config(
      defaultDatabase: String,
      defaultPassword: String,
  ): ConfigValue[Effect, PostgresConfig] =
    (
      env("POSTGRES_HOST").as[Host].default(host"127.0.0.1"),
      env("POSTGRES_PORT").as[Port].default(port"5432"),
      env("POSTGRES_USER").as[String].default(defaultDatabase),
      env("POSTGRES_DB").as[String].default(defaultDatabase),
      env("POSTGRES_PASSWORD").as[String].default(defaultPassword).secret,
      env("POSTGRES_MAX_CONNECTIONS").as[Int](using positiveIntDecoder).default(10),
    ).parMapN { (host, port, user, database, password, maxConnections) =>
      PostgresConfig(
        host,
        port,
        user,
        database,
        password,
        maxConnections,
      )
    }
