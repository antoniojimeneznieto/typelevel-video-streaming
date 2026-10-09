package org.typelevel.video.streaming.traffic

import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{
  BucketBoundaries,
  Counter,
  Histogram,
  Meter,
  MeterProvider,
  UpDownCounter,
}

/** Mirror the generator's independent accounting in LGTM using bounded dimensions. */
trait TrafficMetrics:
  def arrivals(result: String, count: Long): IO[Unit]
  def started: IO[Unit]
  def completed(result: RequestResult, elapsed: FiniteDuration): IO[Unit]
  def cancelled: IO[Unit]

object TrafficMetrics:

  val noop: TrafficMetrics = new TrafficMetrics:
    def arrivals(result: String, count: Long): IO[Unit]                     = IO.unit
    def started: IO[Unit]                                                   = IO.unit
    def completed(result: RequestResult, elapsed: FiniteDuration): IO[Unit] = IO.unit
    def cancelled: IO[Unit]                                                 = IO.unit

  def create(profile: TrafficProfile)(using MeterProvider[IO]): IO[TrafficMetrics] =
    for
      given Meter[IO] <- MeterProvider[IO].get("org.typelevel.video.streaming.traffic")
      arrivals        <- Meter[IO]
                    .counter[Long]("lab.traffic.arrivals")
                    .withUnit("{request}")
                    .withDescription("Scheduled arrivals by admission result")
                    .create
      requests <- Meter[IO]
                    .counter[Long]("lab.traffic.requests")
                    .withUnit("{request}")
                    .withDescription("Completed generator requests by outcome")
                    .create
      cancellations <- Meter[IO]
                         .counter[Long]("lab.traffic.cancellations")
                         .withUnit("{request}")
                         .withDescription("Generator requests cancelled before completion")
                         .create
      duration <- Meter[IO]
                    .histogram[Double]("lab.traffic.request.duration")
                    .withUnit("s")
                    .withDescription("End-to-end generator request duration")
                    .withExplicitBucketBoundaries(
                      BucketBoundaries(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0),
                    )
                    .create
      active <- Meter[IO]
                  .upDownCounter[Long]("lab.traffic.requests.active")
                  .withUnit("{request}")
                  .withDescription("Generator requests currently in flight")
                  .create
    yield Live(
      arrivals,
      requests,
      cancellations,
      duration,
      active,
      Attribute("profile", profile.label),
    )

  private def statusClass(status: Option[Int]): String =
    status.fold("none")(code => if code >= 100 && code < 600 then s"${code / 100}xx" else "other")

  final private case class Live(
      arrivalsCounter: Counter[IO, Long],
      requestsCounter: Counter[IO, Long],
      cancellationsCounter: Counter[IO, Long],
      durationHistogram: Histogram[IO, Double],
      activeCounter: UpDownCounter[IO, Long],
      profile: Attribute[String],
  ) extends TrafficMetrics:
    def arrivals(result: String, count: Long): IO[Unit] =
      if count == 0 then IO.unit
      else arrivalsCounter.add(count, profile, Attribute("result", result))

    def started: IO[Unit] = activeCounter.add(1L, profile)

    def completed(result: RequestResult, elapsed: FiniteDuration): IO[Unit] =
      val attributes = List(
        profile,
        Attribute("operation", result.operation.label),
        Attribute("outcome", result.outcome.label),
        Attribute("status_class", statusClass(result.status)),
      )
      for
        _ <- requestsCounter.add(1L, attributes)
        _ <- durationHistogram.record(elapsed.toNanos.toDouble / 1e9, attributes)
        _ <- activeCounter.add(-1L, profile)
      yield ()

    def cancelled: IO[Unit] =
      cancellationsCounter.add(1L, profile) *> activeCounter.add(-1L, profile)
