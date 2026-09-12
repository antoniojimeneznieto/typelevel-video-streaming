package org.typelevel.video.streaming.backend.runtime.postgres

import cats.effect.std.Console
import cats.effect.{Resource, Temporal}
import fs2.io.net.Network
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.video.streaming.backend.runtime.config.PostgresConfig
import skunk.Session

object Postgres:

  def sessionPool[F[_]: Temporal: Network: Console: Meter: Tracer](
      config: PostgresConfig
  ): Resource[F, Resource[F, Session[F]]] =
    Session
      .Builder[F]
      .withHost(config.host)
      .withPort(config.port)
      .withUserAndPassword(config.user, config.password.value)
      .withDatabase(config.database)
      .pooled(config.maxConnections)
