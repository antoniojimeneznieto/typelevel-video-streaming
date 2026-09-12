package org.typelevel.video.streaming.backend.status

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.port
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import org.typelevel.video.streaming.backend.runtime.config.HttpServerConfig
import org.typelevel.video.streaming.backend.runtime.http.HttpServer
import org.typelevel.video.streaming.backend.runtime.telemetry.Telemetry

object Main extends IOApp.Simple:

  override val run: IO[Unit] =
    Telemetry.resource("status-service").use { otel =>
      given MeterProvider[IO]  = otel.meterProvider
      given TracerProvider[IO] = otel.tracerProvider

      HttpServerConfig.load[IO](port"8080").flatMap { config =>
        HttpServer.run(config, Routes.health.orNotFound)
      }
    }
