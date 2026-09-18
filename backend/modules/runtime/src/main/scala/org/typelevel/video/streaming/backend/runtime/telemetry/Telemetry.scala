package org.typelevel.video.streaming.backend.runtime.telemetry

import cats.effect.unsafe.metrics.IORuntimeMetrics as CEIORuntimeMetrics
import cats.effect.{IO, Resource}
import cats.syntax.functor.*
import org.typelevel.otel4s.oteljava.OtelJava
import io.opentelemetry.api.OpenTelemetry as JOpenTelemetry
import io.opentelemetry.instrumentation.runtimetelemetry.RuntimeTelemetry
import org.typelevel.otel4s.instrumentation.ce.IORuntimeMetrics
import org.typelevel.otel4s.metrics.MeterProvider

object Telemetry:

  def resource(
      serviceName: String,
      runtimeMetrics: => CEIORuntimeMetrics,
  ): Resource[IO, OtelJava[IO]] =
    for {
      otel4s <- OtelJava.autoConfigured[IO](
                  _.addPropertiesSupplier(() => java.util.Map.of("otel.service.name", serviceName)),
                )

      given MeterProvider[IO] = otel4s.meterProvider

      _ <- registerRuntimeTelemetry(otel4s.underlying)
      _ <- IORuntimeMetrics.register[IO](runtimeMetrics, IORuntimeMetrics.Config.default)
    } yield otel4s

  private def registerRuntimeTelemetry(
      openTelemetry: JOpenTelemetry,
  ): Resource[IO, Unit] =
    Resource
      .fromAutoCloseable(IO.delay(RuntimeTelemetry.create(openTelemetry)))
      .void
