package org.typelevel.video.streaming.backend.identity.service

import java.util.concurrent.TimeUnit

import cats.effect.IO
import cats.effect.std.Semaphore
import org.typelevel.otel4s.metrics.{BucketBoundaries, MeterProvider}
import org.typelevel.otel4s.trace.TracerProvider

/** Simulates a synchronous third-party authentication check for the workshop. */
trait LegacyLoginCheck:
  def check: IO[Unit]

object LegacyLoginCheck:
  val noop: LegacyLoginCheck = new LegacyLoginCheck:
    def check: IO[Unit] = IO.unit

  def create(waitMillis: Int, maxConcurrent: Int = 4)(using
      TracerProvider[IO],
      MeterProvider[IO],
  ): IO[LegacyLoginCheck] =
    for
      _ <- IO.raiseUnless(waitMillis >= 0)(
             new IllegalArgumentException("waitMillis must be nonnegative"),
           )
      _ <- IO.raiseUnless(maxConcurrent > 0)(
             new IllegalArgumentException("maxConcurrent must be positive"),
           )
      permits <- Semaphore[IO](maxConcurrent.toLong)
      tracer  <- TracerProvider[IO].get("org.typelevel.video.streaming.identity.legacy-login-check")
      meter   <- MeterProvider[IO].get("org.typelevel.video.streaming.identity.legacy-login-check")
      duration <-
        meter
          .histogram[Double]("identity.login.legacy_check.duration")
          .withUnit("s")
          .withDescription("Synchronous workshop authentication check including permit wait")
          .withExplicitBucketBoundaries(
            BucketBoundaries(0.005, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0),
          )
          .create
    yield new LegacyLoginCheck:
      def check: IO[Unit] =
        if waitMillis == 0 then IO.unit
        else
          tracer.span("identity.login.legacy_check").surround {
            for
              started <- IO.monotonic
              _       <- permits.permit.use { _ =>
                     // Password verification may leave this continuation on a blocking worker.
                     // Yield to compute before calling the synchronous SDK exercise boundary.
                     IO.cede *> IO.delay(Thread.sleep(waitMillis.toLong))
                   }
              finished <- IO.monotonic
              _        <- duration.record((finished - started).toUnit(TimeUnit.SECONDS))
            yield ()
          }
