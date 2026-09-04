package org.typelevel.video.streaming.backend.status

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.port
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.otel4s.middleware.metrics.OtelMetrics
import org.http4s.otel4s.middleware.trace.redact.{PathRedactor, QueryRedactor}
import org.http4s.otel4s.middleware.trace.server.{
  PathAndQueryRedactor,
  ServerMiddleware,
  ServerSpanDataProvider,
}
import org.http4s.server.middleware.{CORS, Metrics}
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.TracerProvider
import org.typelevel.video.streaming.backend.common.config.ServerConfig

object Main extends IOApp.Simple {

  private def createHttpApp(using TracerProvider[IO], MeterProvider[IO]) = {
    val routes = Routes.apply

    for {
      middleware <- ServerMiddleware
                      .builder[IO](
                        ServerSpanDataProvider.openTelemetry(redactor).optIntoClientPort,
                      )
                      .build
      metricsOps <- OtelMetrics.serverMetricsOps[IO]()
    } yield CORS.policy.withAllowOriginAll(
      middleware.wrapHttpRoutes(Metrics(metricsOps)(routes)).orNotFound,
    )
  }

  private val redactor: PathAndQueryRedactor = new PathRedactor.NeverRedact
    with QueryRedactor.NeverRedact {
    /*def redactPath(path: Uri.Path): Uri.Path =
      if (path.isEmpty) path
      else Uri.Path.Root / redact.REDACTED

    def redactQuery(query: Query): Query =
      if (query.isEmpty) query
      else Query(redact.REDACTED -> None)

    def redactFragment(fragment: Fragment): Option[Fragment] =
      Some(if (fragment.isEmpty) fragment else redact.REDACTED)*/

  }

  override val run: IO[Unit] =
    OtelJava.autoConfigured[IO]().use { otel4s =>
      given TracerProvider[IO] = otel4s.tracerProvider
      given MeterProvider[IO]  = otel4s.meterProvider

      for {
        config  <- ServerConfig.load(defaultPort = port"8080")
        httpApp <- createHttpApp
        _       <- EmberServerBuilder
                     .default[IO]
                     .withHost(config.host)
                     .withPort(config.port)
                     .withHttpApp(httpApp)
                     .build
                     .useForever
      } yield ()
    }

}
