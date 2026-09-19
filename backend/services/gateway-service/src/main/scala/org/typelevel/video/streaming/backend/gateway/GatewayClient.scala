package org.typelevel.video.streaming.backend.gateway

import cats.effect.{IO, Resource}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.otel4s.middleware.trace.client.*
import org.typelevel.otel4s.trace.TracerProvider

private[gateway] object GatewayClient:

  def resource(using TracerProvider[IO]): Resource[IO, Client[IO]] =
    EmberClientBuilder.default[IO].build.evalMap(instrument)

  def instrument(client: Client[IO])(using TracerProvider[IO]): IO[Client[IO]] =
    ClientMiddleware
      .builder[IO](ClientSpanDataProvider.openTelemetry(redactor))
      .build
      .map(_.wrapClient(client))

  private val redactor: UriRedactor = new UriRedactor.OnlyRedactUserInfo {}
