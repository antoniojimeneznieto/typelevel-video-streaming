package org.typelevel.video.streaming.backend.identity

import scala.concurrent.duration.*
import cats.effect.{Deferred, IO}
import cats.effect.std.Semaphore
import org.typelevel.otel4s.oteljava.testkit.metrics.{
  MetricExpectation,
  MetricExpectations,
  MetricsTestkit,
}
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import org.typelevel.video.streaming.backend.identity.service.PasswordHasherTelemetry
import weaver.SimpleIOSuite

object PasswordHasherTelemetrySuite extends SimpleIOSuite:
  private def activeIs(kit: MetricsTestkit[IO], value: Long): IO[Boolean] =
    kit.collectMetrics.map(metrics =>
      MetricExpectations.exists(
        metrics,
        MetricExpectation.sum[Long]("identity.password.verify.active").value(value),
      ),
    )

  test("canceling password verification releases its permit and active measurement") {
    MetricsTestkit.inMemory[IO]().use { kit =>
      given MeterProvider[IO]  = kit.meterProvider
      given TracerProvider[IO] = TracerProvider.noop[IO]
      PasswordHasherTelemetry.create.flatMap { telemetry =>
        for
          permits <- Semaphore[IO](1)
          entered <- Deferred[IO, Unit]
          fiber   <- telemetry
                     .verify(permits)(entered.complete(()) *> IO.never)
                     .start
          _         <- entered.get.timeout(2.seconds)
          during    <- activeIs(kit, 1L)
          _         <- fiber.cancel.timeout(2.seconds)
          after     <- activeIs(kit, 0L)
          available <- permits.available
          metrics   <- kit.collectMetrics
          recorded   = MetricExpectations
                       .checkAll(
                         metrics,
                         MetricExpectation
                           .histogram("identity.password.permit.wait.duration")
                           .pointCount(1),
                         MetricExpectation
                           .histogram("identity.password.verify.duration")
                           .pointCount(1),
                       )
                       .isRight
        yield expect.all(during, after, available == 1L, recorded)
      }
    }
  }

  test("queued verification can be canceled before any permit is released") {
    given MeterProvider[IO]  = MeterProvider.noop[IO]
    given TracerProvider[IO] = TracerProvider.noop[IO]
    for
      telemetry <- PasswordHasherTelemetry.create
      permits   <- Semaphore[IO](0)
      fiber     <- telemetry.verify(permits)(IO.unit).start
      _         <- IO.sleep(100.millis)
      done      <- Deferred[IO, Unit]
      canceler  <- (fiber.cancel *> done.complete(())).start
      promptly  <- done.get.as(true).timeoutTo(500.millis, IO.pure(false))
      _         <- permits.release
      _         <- canceler.joinWithNever.timeout(2.seconds)
    yield expect(promptly)
  }

  test("invalid hasher concurrency is an effect failure") {
    given MeterProvider[IO]  = MeterProvider.noop[IO]
    given TracerProvider[IO] = TracerProvider.noop[IO]
    org.typelevel.video.streaming.backend.identity.service.PasswordHasherImpl
      .create(0)
      .attempt
      .map(result => expect(result.left.exists(_.isInstanceOf[IllegalArgumentException])))
  }
