package org.typelevel.video.streaming.backend.runtime.telemetry

import cats.effect.{IO, Resource}
import org.typelevel.otel4s.oteljava.OtelJava

object Telemetry:

  def resource(serviceName: String): Resource[IO, OtelJava[IO]] =
    OtelJava.autoConfigured[IO](
      _.addPropertiesSupplier(() => java.util.Map.of("otel.service.name", serviceName)),
    )
