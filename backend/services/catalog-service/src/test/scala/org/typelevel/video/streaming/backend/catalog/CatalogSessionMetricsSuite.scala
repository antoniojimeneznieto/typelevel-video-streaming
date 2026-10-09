package org.typelevel.video.streaming.backend.catalog

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.{Queue, Semaphore}
import cats.syntax.all.*
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.testkit.metrics.MetricsTestkit
import weaver.SimpleIOSuite

object CatalogSessionMetricsSuite extends SimpleIOSuite:
  private val gaugeName = "catalog.session.wait.max_age"

  private def instrument[A](resource: Resource[IO, A]): Resource[IO, Resource[IO, A]] =
    CatalogSessionMetrics.instrument(resource, 1)(using MeterProvider.noop[IO])

  private def age(kit: MetricsTestkit[IO]): IO[Double] =
    kit.collectMetrics.flatMap { metrics =>
      IO.fromOption(
        metrics
          .find(_.getName == gaugeName)
          .flatMap(_.getDoubleGaugeData.getPoints.asScala.headOption)
          .map(_.getValue),
      )(new IllegalStateException("Expected oldest-wait gauge measurement"))
    }

  private def waiting(kit: MetricsTestkit[IO]): IO[Long] =
    kit.collectMetrics.flatMap { metrics =>
      IO.fromOption(
        metrics
          .find(_.getName == "catalog.session.waiting")
          .flatMap(_.getLongSumData.getPoints.asScala.headOption)
          .map(_.getValue),
      )(new IllegalStateException("Expected waiting count"))
    }

  test("canceling a blocked acquisition completes before the pool is released") {
    for
      pool    <- Semaphore[IO](1)
      entered <- Deferred[IO, Unit]
      done    <- Deferred[IO, Unit]
      result  <-
        instrument(Resource.eval(entered.complete(())) *> pool.permit).use { measured =>
          for
            canceled <-
              pool.permit.use { _ =>
                measured.use_.start.flatMap { fiber =>
                  (entered.get *> (fiber.cancel *> done.complete(())).start *> done.get.as(true))
                    .timeoutTo(2.seconds, IO.pure(false))
                }
              }
            // Release the holder before awaiting a failed cancellation regression.
            _         <- done.get.timeout(2.seconds)
            _         <- measured.use_.timeout(2.seconds)
            available <- pool.available
          yield expect(canceled) && expect(available == 1L)
        }
    yield result
  }

  test("successful and failed uses release their session exactly once") {
    Ref.of[IO, Int](0).flatMap { releases =>
      instrument(Resource.make(IO.unit)(_ => releases.update(_ + 1))).use { measured =>
        for
          _      <- measured.use_
          result <-
            measured.use(_ => IO.raiseError[Unit](new RuntimeException("query failed"))).attempt
          count <- releases.get
        yield expect(result.isLeft) && expect(count == 2)
      }
    }
  }

  test("canceling session use releases the session exactly once") {
    for
      releases <- Ref.of[IO, Int](0)
      entered  <- Deferred[IO, Unit]
      result   <- instrument(Resource.make(IO.unit)(_ => releases.update(_ + 1))).use { measured =>
                  for
                    fiber <- measured.use(_ => entered.complete(()) *> IO.never).start
                    _     <- entered.get *> fiber.cancel.timeout(2.seconds)
                    count <- releases.get
                  yield expect(count == 1)
                }
    yield result
  }

  test("a failed acquisition releases resources already acquired") {
    Ref.of[IO, Int](0).flatMap { releases =>
      instrument(
        Resource.make(IO.unit)(_ => releases.update(_ + 1)) *>
          Resource.eval(IO.raiseError[Unit](new RuntimeException("acquisition failed"))),
      ).use { measured =>
        for
          result <- measured.use_.attempt
          count  <- releases.get
        yield expect(result.isLeft) && expect(count == 1)
      }
    }
  }

  test(
    "collected age grows without pool events, follows remaining waiters, and clears on cancellation",
  ) {
    MetricsTestkit.inMemory[IO]().use { kit =>
      given MeterProvider[IO] = kit.meterProvider
      for
        entered <- Queue.unbounded[IO, Unit]
        pool    <- Semaphore[IO](0)
        result  <- CatalogSessionMetrics
                    .instrument(
                      Resource.eval(entered.offer(())) *> pool.permit,
                      1,
                    )
                    .use { measured =>
                      for
                        idle       <- age(kit)
                        assertions <- Resource.make(measured.use_.start)(_.cancel).use { oldest =>
                                        for
                                          _            <- entered.take.timeout(2.seconds)
                                          first        <- age(kit)
                                          _            <- IO.sleep(100.millis)
                                          later        <- age(kit)
                                          youngerStart <- IO.monotonic
                                          assertions   <-
                                            Resource.make(measured.use_.start)(_.cancel).use {
                                              younger =>
                                                for
                                                  _           <- entered.take.timeout(2.seconds)
                                                  two         <- waiting(kit)
                                                  _           <- oldest.cancel.timeout(2.seconds)
                                                  remaining   <- age(kit)
                                                  collectedAt <- IO.monotonic
                                                  one         <- waiting(kit)
                                                  _           <- younger.cancel.timeout(2.seconds)
                                                  cleared     <- age(kit)
                                                  zero        <- waiting(kit)
                                                yield expect.all(
                                                  idle == 0.0,
                                                  later > first,
                                                  two == 2L,
                                                  one == 1L,
                                                  remaining >= 0.0,
                                                  remaining <= (collectedAt - youngerStart).toNanos.toDouble / 1e9,
                                                  cleared == 0.0,
                                                  zero == 0L,
                                                )
                                            }
                                        yield assertions
                                      }
                      yield assertions
                    }
      yield result
    }
  }

  test(
    "successful and failed acquisition clear the gauge; closing instrumentation unregisters it",
  ) {
    MetricsTestkit.inMemory[IO]().use { kit =>
      given MeterProvider[IO] = kit.meterProvider
      for
        fail   <- Ref.of[IO, Boolean](false)
        inside <- CatalogSessionMetrics
                    .instrument(
                      Resource.eval(fail.get.flatMap { failing =>
                        if failing then
                          IO.raiseError[Unit](new RuntimeException("acquisition failed"))
                        else IO.unit
                      }),
                      1,
                    )
                    .use { measured =>
                      for
                        _          <- measured.use_
                        successAge <- age(kit)
                        _          <- fail.set(true)
                        result     <- measured.use_.attempt
                        failedAge  <- age(kit)
                        count      <- waiting(kit)
                      yield expect.all(
                        successAge == 0.0,
                        result.isLeft,
                        failedAge == 0.0,
                        count == 0L,
                      )
                    }
        after <- kit.collectMetrics
      yield inside && expect(!after.exists(_.getName == gaugeName))
    }
  }
