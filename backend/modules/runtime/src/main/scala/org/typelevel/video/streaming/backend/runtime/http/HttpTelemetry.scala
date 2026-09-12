package org.typelevel.video.streaming.backend.runtime.http

import cats.data.OptionT
import cats.effect.Temporal
import cats.syntax.all.*
import org.http4s.{HttpApp, HttpRoutes, Query, Uri}
import org.http4s.otel4s.middleware.metrics.OtelMetrics
import org.http4s.otel4s.middleware.trace.redact.{PathRedactor, QueryRedactor}
import org.http4s.otel4s.middleware.trace.server.{
  PathAndQueryRedactor,
  ServerMiddleware,
  ServerSpanDataProvider
}
import org.http4s.server.middleware.Metrics
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider

object HttpTelemetry:

  // Requests can contain IDs, credentials, or arbitrary search text in their URLs.
  // Omit raw paths and queries until endpoint-aware route templates are configured.
  private val redactor: PathAndQueryRedactor = new PathRedactor with QueryRedactor:
    override def redactPath(path: Uri.Path): Uri.Path = Uri.Path.empty
    override def redactQuery(query: Query): Query     = Query.empty

  def apply[F[_]: Temporal: MeterProvider: TracerProvider](app: HttpApp[F]): F[HttpApp[F]] =
    for
      tracing <- ServerMiddleware.builder[F](ServerSpanDataProvider.openTelemetry(redactor)).build
      metrics <- OtelMetrics.serverMetricsOps[F]()
      routes   = HttpRoutes[F](request => OptionT.liftF(app(request)))
    yield tracing.wrapHttpApp(Metrics(metrics)(routes).orNotFound)
