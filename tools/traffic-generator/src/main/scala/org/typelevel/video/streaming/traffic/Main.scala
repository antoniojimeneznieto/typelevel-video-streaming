package org.typelevel.video.streaming.traffic

import scala.concurrent.duration.Duration

import cats.effect.{ExitCode, IO, IOApp, Ref}
import cats.effect.std.Env
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.otel4s.context.LocalProvider
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.context.{Context, IOLocalContextStorage}
import org.typelevel.otel4s.oteljava.OtelJava

object Main extends IOApp:
  private given LocalProvider[IO, Context] = IOLocalContextStorage.localProvider[IO]

  def run(args: List[String]): IO[ExitCode] =
    Cli.command.parse(args) match
      case Left(help) if help.errors.isEmpty => IO.println(help).as(ExitCode.Success)
      case Left(help) => IO.consoleForIO.errorln(help).as(ExitCode(2))
      case Right(config) => runTraffic(config)

  private def runTraffic(config: Config): IO[ExitCode] =
    val telemetry = cats.effect.Resource.eval(Env[IO].get("OTEL_EXPORTER_OTLP_ENDPOINT")).flatMap {
      case Some(_) =>
        cats.effect.Resource.eval(Env[IO].get("HOSTNAME")).flatMap { hostname =>
          OtelJava
            .autoConfigured[IO](
              _.addPropertiesSupplier(() =>
                hostname.fold(java.util.Map.of("otel.service.name", "traffic-generator"))(id =>
                  java.util.Map.of(
                    "otel.service.name",
                    "traffic-generator",
                    "otel.resource.attributes",
                    s"service.instance.id=$id",
                  ),
                ),
              ),
            )
            .map(_.meterProvider)
        }
      case None => cats.effect.Resource.pure[IO, MeterProvider[IO]](MeterProvider.noop[IO])
    }

    (
      telemetry,
      EmberClientBuilder
        .default[IO]
        .withMaxTotal(config.maxConcurrent)
        .withMaxPerKey(_ => config.maxConcurrent)
        .withTimeout(Duration.Inf)
        .withRetryPolicy((_, _, _) => None)
        .build,
    ).tupled
      .use { (provider, client) =>
        for
          metrics <- TrafficMetrics.create(provider)
          stats   <- Ref.of[IO, Stats](Stats())
          start   <- IO.monotonic
          report   = (kind: String) =>
                     (stats.get, IO.monotonic, IO.realTime)
                       .mapN((s, now, wallTime) =>
                         s.json(kind, now - start)
                           .deepMerge(
                             Json.obj(
                               "timestamp_epoch_ms" -> Json.fromLong(wallTime.toMillis),
                               "requested_rate" -> Json.fromInt(config.rate),
                               "max_concurrent" -> Json.fromInt(config.maxConcurrent),
                             ),
                           )
                           .noSpaces,
                       )
                       .flatMap(IO.println)
          progress = Stream.awakeEvery[IO](config.reportInterval).evalMap(_ => report("progress"))
          _       <-
            Stream
              .eval(
                Traffic.run(config, CatalogTraffic.request(client, config.baseUrl), stats, metrics),
              )
              .concurrently(progress)
              .compile
              .drain
              .guarantee(report("summary"))
          result <- stats.get
        yield
          if !result.valid then ExitCode(2)
          else if result.failed > 0 then ExitCode(1)
          else ExitCode.Success
      }
