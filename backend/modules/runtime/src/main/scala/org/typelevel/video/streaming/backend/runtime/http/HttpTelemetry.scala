package org.typelevel.video.streaming.backend.runtime.http

import cats.data.OptionT
import cats.effect.Temporal
import cats.syntax.all.*
import org.http4s.otel4s.middleware.metrics.OtelMetrics
import org.http4s.otel4s.middleware.server.RouteClassifier
import org.http4s.otel4s.middleware.trace.redact.{PathRedactor, QueryRedactor}
import org.http4s.otel4s.middleware.trace.server.{
  PathAndQueryRedactor,
  ServerMiddleware,
  ServerSpanDataProvider,
}
import org.http4s.server.middleware.Metrics
import org.http4s.{HttpApp, HttpRoutes}
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider

object HttpTelemetry:

  private val redactor: PathAndQueryRedactor =
    new PathRedactor.NeverRedact with QueryRedactor.NeverRedact

  def apply[F[_]: Temporal: MeterProvider: TracerProvider](
      app: HttpApp[F],
      routeClassifier: RouteClassifier,
  ): F[HttpApp[F]] =
    val boundedClassifier: RouteClassifier =
      request => routeClassifier.classify(request).orElse(Some("unmatched"))

    for
      tracing <-
        ServerMiddleware
          .builder[F](
            ServerSpanDataProvider.openTelemetry(redactor).withRouteClassifier(boundedClassifier),
          )
          .build
      metrics <- OtelMetrics.serverMetricsOps[F]()
      routes   = HttpRoutes[F](request => OptionT.liftF(app(request)))
    yield tracing.wrapHttpApp(
      Metrics(metrics, classifierF = req => boundedClassifier.classify(req.requestPrelude))(
        routes,
      ).orNotFound,
    )
