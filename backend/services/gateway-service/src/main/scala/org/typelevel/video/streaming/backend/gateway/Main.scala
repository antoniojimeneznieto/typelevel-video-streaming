package org.typelevel.video.streaming.backend.gateway

import cats.effect.{IO, IOApp}
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import org.typelevel.video.streaming.backend.runtime.http.HttpServer
import org.typelevel.video.streaming.backend.runtime.telemetry.Telemetry

object Main extends IOApp.Simple:

  override val run: IO[Unit] =
    Telemetry.resource("gateway-service", runtime.metrics).use { otel =>
      given MeterProvider[IO]  = otel.meterProvider
      given TracerProvider[IO] = otel.tracerProvider

      AppConfig.load[IO].flatMap { config =>
        GatewayClient.resource(config.upstreams).use { client =>
          HttpServer.run(
            config.server,
            GatewayRoutes(client, config.upstreams),
            GatewayRouteClassifier.routes,
          )
        }
      }
    }
