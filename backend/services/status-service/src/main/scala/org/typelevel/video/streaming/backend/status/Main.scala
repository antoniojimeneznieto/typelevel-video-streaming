package org.typelevel.video.streaming.backend.status

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.port
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.CORS
import org.typelevel.video.streaming.backend.common.config.ServerConfig

object Main extends IOApp.Simple {

  private val httpApp =
    CORS.policy.withAllowOriginAll
      .apply(Routes.apply.orNotFound)

  override val run: IO[Unit] =
    ServerConfig.load(defaultPort = port"8080").flatMap { config =>
      EmberServerBuilder
        .default[IO]
        .withHost(config.host)
        .withPort(config.port)
        .withHttpApp(httpApp)
        .build
        .useForever
    }

}
