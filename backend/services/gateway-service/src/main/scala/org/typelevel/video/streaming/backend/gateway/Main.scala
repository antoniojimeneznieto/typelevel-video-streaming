package org.typelevel.video.streaming.backend.gateway

import cats.effect.{IO, IOApp}
import org.http4s.otel4s.middleware.server.RouteClassifier
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
        GatewayClient.resource.use { client =>
          val routeClassifier: RouteClassifier = req => {
            req.uri.path.segments.map(_.encoded) match {
              case Vector("api", service, _*) => Some(s"/api/$service/{}")
              case _ => None
            }
          }

          HttpServer.run(
            config.server,
            GatewayRoutes(client, config.upstreams),
            routeClassifier,
          )
        }
      }
    }
