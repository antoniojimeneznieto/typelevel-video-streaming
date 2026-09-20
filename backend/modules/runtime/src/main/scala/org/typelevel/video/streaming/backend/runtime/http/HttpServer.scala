package org.typelevel.video.streaming.backend.runtime.http

import cats.effect.Async
import cats.syntax.all.*
import fs2.io.net.Network
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.HttpApp
import org.http4s.otel4s.middleware.server.RouteClassifier
import org.http4s.server.middleware.CORS
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import org.typelevel.video.streaming.backend.runtime.config.HttpServerConfig

object HttpServer:

  def run[F[_]: Async: Network: MeterProvider: TracerProvider](
      config: HttpServerConfig,
      httpApp: HttpApp[F],
      routeClassifier: RouteClassifier,
  ): F[Unit] =
    HttpTelemetry(CORS.policy.withAllowOriginAll(httpApp), routeClassifier)
      .flatMap { instrumented =>
        EmberServerBuilder
          .default[F]
          .withHost(config.host)
          .withPort(config.port)
          .withHttpApp(instrumented)
          .build
          .useForever
          .void
      }
