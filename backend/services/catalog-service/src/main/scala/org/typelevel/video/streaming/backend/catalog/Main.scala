package org.typelevel.video.streaming.backend.catalog

import cats.effect.{IO, IOApp, Resource}
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

      val resources = for
        config    <- Resource.eval(AppConfig.load[IO])
        sessions  <- Postgres.sessionPool[IO](config.postgres)
        measured  <- CatalogSessionMetrics.instrument(sessions, config.postgres.maxConnections)
        repository = new CatalogRepositoryImpl(measured)
        service    = new CatalogServiceImpl(repository)
        routes    <- SimpleRestJsonBuilder.routes(service).resource
      yield (config, routes.orNotFound)

      resources.use { (config, app) =>
        HttpServer.run(config.server, app, SmithyRouteClassifier(CatalogService))
      }
    }
