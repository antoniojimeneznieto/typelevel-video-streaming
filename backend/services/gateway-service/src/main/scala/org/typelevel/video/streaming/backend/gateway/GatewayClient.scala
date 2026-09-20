package org.typelevel.video.streaming.backend.gateway

import cats.effect.{IO, Resource}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.client.middleware.Metrics
import org.http4s.otel4s.middleware.trace.client.*
import org.http4s.otel4s.middleware.metrics.*
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider

private[gateway] object GatewayClient:

  def resource(
      config: UpstreamConfig,
  )(using TracerProvider[IO], MeterProvider[IO]): Resource[IO, Client[IO]] =
    EmberClientBuilder.default[IO].build.evalMap(instrument(_, config))

  def instrument(
      client: Client[IO],
      config: UpstreamConfig,
  )(using TracerProvider[IO], MeterProvider[IO]): IO[Client[IO]] =
    for {
      metricsOps <- OtelMetrics.clientMetricsOps[IO]()
      tracing    <- ClientMiddleware
                   .builder[IO](
                     ClientSpanDataProvider
                       .openTelemetry(redactor)
                       .withUrlTemplateClassifier(GatewayClientRouteClassifier.urlTemplates(config)),
                   )
                   .build
    } yield tracing.wrapClient(
      Metrics(metricsOps, classifierF = GatewayClientRouteClassifier(config))(client),
    )

  private val redactor: UriRedactor = new UriRedactor.OnlyRedactUserInfo {}
