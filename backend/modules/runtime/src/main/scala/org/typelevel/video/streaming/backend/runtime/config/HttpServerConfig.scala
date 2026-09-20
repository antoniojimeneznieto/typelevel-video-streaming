package org.typelevel.video.streaming.backend.runtime.config

import cats.syntax.all.*
import ciris.{ConfigDecoder, ConfigValue, Effect, env}
import com.comcast.ip4s.{Host, Port, host}

final case class HttpServerConfig(host: Host, port: Port)

object HttpServerConfig:

  private given ConfigDecoder[String, Host] =
    ConfigDecoder[String].mapOption("Host")(Host.fromString)

  private given ConfigDecoder[String, Port] =
    ConfigDecoder[String].mapOption("Port")(Port.fromString)

  def config(defaultPort: Port): ConfigValue[Effect, HttpServerConfig] =
    (
      env("HOST").as[Host].default(host"127.0.0.1"),
      env("PORT").as[Port].default(defaultPort),
    ).parMapN(HttpServerConfig.apply)
