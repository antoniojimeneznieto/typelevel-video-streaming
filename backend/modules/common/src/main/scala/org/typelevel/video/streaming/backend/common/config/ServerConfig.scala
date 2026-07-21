package org.typelevel.video.streaming.backend.common.config

import cats.effect.IO
import cats.syntax.all.*
import ciris.*
import com.comcast.ip4s.{Host, Port, host}

final case class ServerConfig(host: Host, port: Port)

object ServerConfig:

  private given ConfigDecoder[String, Host] =
    ConfigDecoder[String].mapOption("Host")(Host.fromString)

  private given ConfigDecoder[String, Port] =
    ConfigDecoder[String].mapOption("Port")(Port.fromString)

  def fromEnv(defaultPort: Port): ConfigValue[Effect, ServerConfig] =
    (
      env("HOST").as[Host].default(host"0.0.0.0"),
      env("PORT").as[Port].default(defaultPort)
    ).parMapN(ServerConfig.apply)

  def load(defaultPort: Port): IO[ServerConfig] =
    fromEnv(defaultPort).load[IO]
