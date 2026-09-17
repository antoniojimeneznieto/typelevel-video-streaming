package org.typelevel.video.streaming.backend.runtime.postgres

import cats.effect.std.Console
import cats.effect.{Resource, Temporal}
import fs2.io.net.Network
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import org.typelevel.video.streaming.backend.runtime.config.PostgresConfig
import skunk.Session
import io.opentelemetry.instrumentation.api.incubator.semconv.db.{SqlDialect, SqlQuery, SqlQueryAnalyzer}
import skunk.telemetry.{QueryAnalyzer, TelemetryConfig}

object Postgres:

  def sessionPool[F[_]: Temporal: Network: Console: MeterProvider: TracerProvider](
      config: PostgresConfig,
  ): Resource[F, Resource[F, Session[F]]] =
    Session
      .Builder[F]
      .withHost(config.host)
      .withPort(config.port)
      .withUserAndPassword(config.user, config.password.value)
      .withDatabase(config.database)
      .withTelemetryConfig(
        TelemetryConfig.default
          .withProtocolSpans(TelemetryConfig.ProtocolSpans.Disabled)
          .withQueryAnalyzer(queryAnalyzer)
      )
      .pooled(config.maxConnections)

  private def queryAnalyzer: QueryAnalyzer =
    val dialect = SqlDialect.DOUBLE_QUOTES_ARE_IDENTIFIERS
    val delegate = SqlQueryAnalyzer.create(true)
    QueryAnalyzer { sql =>
      Option(delegate.analyzeWithSummary(sql, dialect)).map { (q: SqlQuery) =>
        QueryAnalyzer.Analysis(
          queryText = Option(q.getQueryText),
          storedProcedureName = Option(q.getStoredProcedureName),
          querySummary = Option(q.getQuerySummary)
        )
      }
    }

