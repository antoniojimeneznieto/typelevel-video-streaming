package org.typelevel.video.streaming.backend.status

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.port
import org.typelevel.video.streaming.backend.runtime.config.HttpServerConfig
import org.typelevel.video.streaming.backend.runtime.http.HttpServer

object Main extends IOApp.Simple:

  override val run: IO[Unit] =
    HttpServerConfig.load[IO](port"8080").flatMap { config =>
      HttpServer.run(config, Routes.health.orNotFound)
    }
