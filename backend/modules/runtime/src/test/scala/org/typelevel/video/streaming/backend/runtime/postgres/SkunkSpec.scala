package org.typelevel.video.streaming.backend.runtime.postgres

import scala.io.Source
import scala.util.Using

import cats.effect.{IO, Resource}
import org.testcontainers.images.builder.Transferable
import org.testcontainers.postgresql.PostgreSQLContainer
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import skunk.Session

trait SkunkSpec:

  protected def initScript: String

  private given MeterProvider[IO]  = MeterProvider.noop[IO]
  private given TracerProvider[IO] = TracerProvider.noop[IO]

  protected def sessionPool: Resource[IO, Resource[IO, Session[IO]]] =
    for
      container <- Resource
                     .make(IO.blocking {
                       val script = Using.resource(Source.fromResource(initScript))(_.mkString)
                       new PostgreSQLContainer("postgres:17")
                         .withCopyToContainer(
                           Transferable.of(script),
                           "/docker-entrypoint-initdb.d/init.sql",
                         )
                     })(container => IO.blocking(container.stop()))
                     .evalTap(container => IO.blocking(container.start()))
      sessions <- Session
                    .Builder[IO]
                    .withHost(container.getHost)
                    .withPort(container.getMappedPort(5432).intValue)
                    .withUserAndPassword(container.getUsername, container.getPassword)
                    .withDatabase(container.getDatabaseName)
                    .pooled(4)
    yield sessions
