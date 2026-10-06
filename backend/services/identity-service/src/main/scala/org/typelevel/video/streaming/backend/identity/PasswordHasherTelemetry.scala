package org.typelevel.video.streaming.backend.identity.service

import java.util.concurrent.TimeUnit

import cats.effect.IO
import cats.effect.std.Semaphore
import org.typelevel.otel4s.metrics.{BucketBoundaries, Histogram, MeterProvider, UpDownCounter}
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}

/** Fixed-name instrumentation: no credentials, user IDs, or hashes are recorded. */
final class PasswordHasherTelemetry private (
    tracer: Tracer[IO],
    permitWait: Histogram[IO, Double],
    verifyDuration: Histogram[IO, Double],
    active: UpDownCounter[IO, Long],
):
  def verify[A](permits: Semaphore[IO])(work: IO[A]): IO[A] =
    tracer.span("identity.password.verify").surround {
      for
        started <- IO.monotonic
        result  <- tracer
                    .span("identity.password.permit.wait")
                    .surround(permits.acquire)
                    .bracket { _ =>
                      for
                        acquired <- IO.monotonic
                        _        <- permitWait.record((acquired - started).toUnit(TimeUnit.SECONDS))
                        result   <- active
                                    .inc()
                                    .bracket(_ =>
                                      tracer.span("identity.password.verify.work").surround(work),
                                    )(_ => active.dec())
                      yield result
                    }(_ => permits.release)
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
      meter  <- MeterProvider[IO].get("org.typelevel.video.streaming.identity.password")
      tracer <- TracerProvider[IO].get("org.typelevel.video.streaming.identity.password")
      wait   <- meter
                .histogram[Double]("identity.password.permit.wait.duration")
                .withUnit("s")
                .withDescription("Time waiting for a password verification permit")
                .withExplicitBucketBoundaries(
                  BucketBoundaries(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0),
                )
                .create
      duration <-
        meter
          .histogram[Double]("identity.password.verify.duration")
          .withUnit("s")
          .withDescription("Password verification duration including permit wait")
          .withExplicitBucketBoundaries(
            BucketBoundaries(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0),
          )
          .create
      active <- meter
                  .upDownCounter[Long]("identity.password.verify.active")
                  .withUnit("{verification}")
                  .withDescription("Password verifications currently holding a permit")
                  .create
    yield new PasswordHasherTelemetry(tracer, wait, duration, active)
