package org.typelevel.video.streaming.backend.identity.service

import java.util.concurrent.TimeUnit

import cats.effect.{IO, Resource}
import cats.effect.std.Semaphore
import org.typelevel.otel4s.metrics.{
  BucketBoundaries,
  Histogram,
  Meter,
  MeterProvider,
  UpDownCounter,
}
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}

/** Fixed-name instrumentation: no credentials, user IDs, or hashes are recorded. */
final class PasswordHasherTelemetry private (
    permitWait: Histogram[IO, Double],
    verifyDuration: Histogram[IO, Double],
    active: UpDownCounter[IO, Long],
)(using Tracer[IO]):
  def verify[A](permits: Semaphore[IO])(work: IO[A]): IO[A] =
    Tracer[IO].span("identity.password.verify").surround {
      for
        started <- IO.monotonic
        result  <-
          Resource
            .makeFull[IO, Unit](poll =>
              Tracer[IO].span("identity.password.permit.wait").surround(poll(permits.acquire)),
            )(_ => permits.release)
            .use { _ =>
              for
                acquired <- IO.monotonic
                _        <- permitWait.record((acquired - started).toUnit(TimeUnit.SECONDS))
                result   <- Resource.make(active.inc())(_ => active.dec()).use { _ =>
                            Tracer[IO].span("identity.password.verify.work").surround(work)
                          }
              yield result
            }
            .guarantee(
              IO.monotonic.flatMap(finished =>
                verifyDuration.record((finished - started).toUnit(TimeUnit.SECONDS)),
              ),
            )
      yield result
    }

object PasswordHasherTelemetry:
  def create(using TracerProvider[IO], MeterProvider[IO]): IO[PasswordHasherTelemetry] =
    for
      given Meter[IO]  <- MeterProvider[IO].get("org.typelevel.video.streaming.identity.password")
      given Tracer[IO] <- TracerProvider[IO].get("org.typelevel.video.streaming.identity.password")
      wait             <- Meter[IO]
                .histogram[Double]("identity.password.permit.wait.duration")
                .withUnit("s")
                .withDescription("Time waiting for a password verification permit")
                .withExplicitBucketBoundaries(
                  BucketBoundaries(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0),
                )
                .create
      duration <-
        Meter[IO]
          .histogram[Double]("identity.password.verify.duration")
          .withUnit("s")
          .withDescription("Password verification duration including permit wait")
          .withExplicitBucketBoundaries(
            BucketBoundaries(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0),
          )
          .create
      active <- Meter[IO]
                  .upDownCounter[Long]("identity.password.verify.active")
                  .withUnit("{verification}")
                  .withDescription("Password verifications currently holding a permit")
                  .create
    yield new PasswordHasherTelemetry(wait, duration, active)
