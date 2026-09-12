package org.typelevel.video.streaming.backend.catalog

import cats.effect.{IO, IOApp}
import org.http4s.HttpApp
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.video.streaming.backend.catalog.config.AppConfig
import org.typelevel.video.streaming.backend.catalog.repository.CatalogRepositoryImpl
import org.typelevel.video.streaming.backend.catalog.service.CatalogServiceImpl
import org.typelevel.video.streaming.backend.runtime.http.HttpServer
import org.typelevel.video.streaming.backend.runtime.postgres.Postgres
import smithy4s.http4s.SimpleRestJsonBuilder

object Main extends IOApp.Simple:

  private given Meter[IO]  = Meter.noop[IO]
  private given Tracer[IO] = Tracer.noop[IO]

  override val run: IO[Unit] =
    AppConfig.load[IO].flatMap { config =>
      Postgres.sessionPool[IO](config.postgres).use { sessions =>
        val repository = new CatalogRepositoryImpl(sessions)
        val service    = new CatalogServiceImpl(repository)

        SimpleRestJsonBuilder
          .routes(service)
          .resource
          .use { catalogRoutes =>
            val app: HttpApp[IO] =
              (catalogRoutes).orNotFound

            HttpServer.run(config.server, app)
          }
      }
    }
