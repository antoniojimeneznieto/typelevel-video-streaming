package org.typelevel.video.streaming.traffic

import scala.concurrent.duration.Duration

import cats.effect.std.Console
import cats.effect.{ExitCode, IO, IOApp, Ref, Resource}
import cats.effect.std.Env
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.otel4s.context.LocalProvider
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.context.{Context, IOLocalContextStorage}
import org.typelevel.otel4s.oteljava.OtelJava

object TrafficGeneratorMain extends IOApp:
  private given LocalProvider[IO, Context] = IOLocalContextStorage.localProvider[IO]

  def run(args: List[String]): IO[ExitCode] =
    Cli.command.parse(args) match
      case Left(help) if help.errors.isEmpty => IO.println(help).as(ExitCode.Success)
      case Left(help) => Console[IO].errorln(help).as(ExitCode(2))
      case Right(config) => runTraffic(config)

  private def runTraffic(config: Config): IO[ExitCode] =
    val telemetry = Resource.eval(Env[IO].get("OTEL_EXPORTER_OTLP_ENDPOINT")).flatMap {
      case Some(_) =>
        Resource.eval(Env[IO].get("HOSTNAME")).flatMap { hostname =>
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
      case None => Resource.pure[IO, MeterProvider[IO]](MeterProvider.noop[IO])
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
        given MeterProvider[IO] = provider
        for
          metrics  <- TrafficMetrics.create(config.profile)
          stats    <- Ref.of[IO, Stats](Stats())
          previous <- Ref.of[IO, Stats](Stats())
          request  <- prepareRequest(config, client)
          start    <- IO.monotonic
          report    = (kind: String) =>
                     for
                       s        <- stats.get
                       prior    <- previous.getAndSet(s)
                       now      <- IO.monotonic
                       wallTime <- IO.realTime
                       json      = s.json(kind, now - start, config.profile.label)
                                .deepMerge(
                                  Json.obj(
                                    "timestamp_epoch_ms" -> Json.fromLong(wallTime.toMillis),
                                    "requested_rate" -> Json.fromInt(config.rate),
                                    "max_concurrent" -> Json.fromInt(config.maxConcurrent),
                                    "window" -> s.window(prior),
                                  ),
                                )
                                .noSpaces
                       _ <- IO.println(json)
                     yield ()
          progress = Stream.awakeEvery[IO](config.reportInterval).evalMap(_ => report("progress"))
          _       <-
            Stream
              .eval(
                Traffic.run(config, request, stats, metrics),
              )
              .concurrently(progress)
              .concurrently(Stream.eval(request.maintenance))
              .compile
              .drain
              .guarantee(report("summary"))
          result <- stats.get
        yield
          if !result.valid then ExitCode(2)
          else if result.failed > 0 then ExitCode(1)
          else ExitCode.Success
      }

  private[traffic] def prepareRequest(
      config: Config,
      client: org.http4s.client.Client[IO],
  ): IO[Workload] =
    val prepare = config.profile match
      case TrafficProfile.Playback =>
        PlaybackTraffic.prepare(client, config.baseUrl, config.modernPercent)
      case TrafficProfile.Identity =>
        IdentityTraffic.prepare(client, config.baseUrl, config.loginPercent)
      case TrafficProfile.CatalogSoak =>
        IO.pure(
          Workload(slot =>
            PreparedRequest(
              CatalogTraffic.soakOperation(slot),
              CatalogTraffic.soakRequest(client, config.baseUrl, slot),
            ),
          ),
        )
      case TrafficProfile.CatalogReads =>
        IO.pure(Workload(slot =>
          val operation = CatalogTraffic.soakOperation(slot)
          PreparedRequest(
            operation,
            CatalogTraffic.requestOperation(client, config.baseUrl, operation, None),
          ),
        ))
      case TrafficProfile.CatalogCourses =>
        IO.pure(
          Workload(_ =>
            PreparedRequest(Operation.Courses, CatalogTraffic.request(client, config.baseUrl)),
          ),
        )
    prepare.timeoutTo(
      config.setupTimeout,
      IO.raiseError(
        new java.util.concurrent.TimeoutException(
          s"Actor preparation exceeded ${config.setupTimeout}; measured traffic did not start",
        ),
      ),
    )
