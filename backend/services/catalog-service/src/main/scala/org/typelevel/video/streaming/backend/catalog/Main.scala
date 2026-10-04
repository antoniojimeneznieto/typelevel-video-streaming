package org.typelevel.video.streaming.backend.catalog

import cats.effect.{IO, IOApp}
import org.http4s.HttpApp
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import org.typelevel.video.streaming.backend.catalog.api.CatalogService
import org.typelevel.video.streaming.backend.catalog.config.AppConfig
import org.typelevel.video.streaming.backend.catalog.repository.CatalogRepositoryImpl
import org.typelevel.video.streaming.backend.catalog.service.CatalogServiceImpl
import org.typelevel.video.streaming.backend.runtime.http.{HttpServer, SmithyRouteClassifier}
import org.typelevel.video.streaming.backend.runtime.postgres.Postgres
import org.typelevel.video.streaming.backend.runtime.telemetry.Telemetry
import smithy4s.http4s.SimpleRestJsonBuilder

object Main extends IOApp.Simple:

  override val run: IO[Unit] =
    Telemetry.resource("catalog-service", runtime.metrics).use { otel =>
      given MeterProvider[IO]  = otel.meterProvider
      given TracerProvider[IO] = otel.tracerProvider

      AppConfig.load[IO].flatMap { config =>
        Postgres.sessionPool[IO](config.postgres).use { sessions =>
          CatalogSessionMetrics
            .instrument(sessions, otel.meterProvider, config.postgres.maxConnections)
            .flatMap { measuredSessions =>
              val repository = new CatalogRepositoryImpl(measuredSessions)
              val service    = new CatalogServiceImpl(repository)

              SimpleRestJsonBuilder
                .routes(service)
                .resource
                .use { catalogRoutes =>
                  val app: HttpApp[IO] = catalogRoutes.orNotFound
                  val routeClassifier  = SmithyRouteClassifier(CatalogService)

                  HttpServer.run(config.server, app, routeClassifier)
                }
            }
        }
      }
    }
