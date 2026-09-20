package org.typelevel.video.streaming.backend.runtime.telemetry

import cats.effect.unsafe.metrics.IORuntimeMetrics as CEIORuntimeMetrics
import cats.effect.{IO, Resource}
import cats.effect.std.Env
import cats.syntax.functor.*
import org.typelevel.otel4s.oteljava.OtelJava
import io.opentelemetry.api.OpenTelemetry as JOpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.instrumentation.runtimetelemetry.RuntimeTelemetry
import org.typelevel.otel4s.context.LocalProvider
import org.typelevel.otel4s.instrumentation.ce.IORuntimeMetrics
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.context.{Context, IOLocalContextStorage}
import org.typelevel.otel4s.semconv.attributes.ServiceAttributes

object Telemetry:

  private given LocalProvider[IO, Context] = IOLocalContextStorage.localProvider[IO]

  def resource(
      serviceName: String,
      runtimeMetrics: => CEIORuntimeMetrics,
  ): Resource[IO, OtelJava[IO]] =
    for {
      hostname <- Resource.eval(Env[IO].get("HOSTNAME"))
      otel4s   <- OtelJava.autoConfigured[IO](
                  _.addPropertiesSupplier(() =>
                    hostname.fold(
                      java.util.Map.of(ServiceAttributes.ServiceName.name, serviceName),
                    )(instanceId =>
                      java.util.Map.of(
                        ServiceAttributes.ServiceName.name,
                        serviceName,
                        "otel.resource.attributes",
                        s"service.instance.id=$instanceId",
                      ),
                    ),
                  ),
                )

      _ <- Resource.eval(IO.delay(OpenTelemetryAppender.install(otel4s.underlying)))

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
