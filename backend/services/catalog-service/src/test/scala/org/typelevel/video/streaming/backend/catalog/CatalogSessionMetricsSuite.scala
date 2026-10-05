package org.typelevel.video.streaming.backend.catalog

import scala.concurrent.duration.*

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import org.typelevel.otel4s.metrics.MeterProvider
import weaver.SimpleIOSuite

object CatalogSessionMetricsSuite extends SimpleIOSuite:
  private def instrument[A](resource: Resource[IO, A]): IO[Resource[IO, A]] =
    CatalogSessionMetrics.instrument(resource, MeterProvider.noop[IO], 1)

  test("canceling a blocked acquisition completes before the pool is released") {
    for
      pool     <- Semaphore[IO](1)
      entered  <- Deferred[IO, Unit]
      done     <- Deferred[IO, Unit]
      measured <- instrument(Resource.eval(entered.complete(())) *> pool.permit)
      canceled <-
        pool.permit.use { _ =>
          measured.use_.start.flatMap { fiber =>
            (entered.get *> (fiber.cancel *> done.complete(())).start *> done.get.as(true))
              .timeoutTo(2.seconds, IO.pure(false))
          }
        }
      // If cancellation failed, releasing the holder lets the old buggy fiber
      // terminate too: the regression test never leaves an uncancelable waiter.
      _         <- done.get.timeout(2.seconds)
      _         <- measured.use_.timeout(2.seconds)
      available <- pool.available
    yield expect(canceled) && expect(available == 1L)
  }

  test("successful and failed uses release their session exactly once") {
    for
      releases <- Ref.of[IO, Int](0)
      measured <- instrument(Resource.make(IO.unit)(_ => releases.update(_ + 1)))
      _        <- measured.use_
      result <- measured.use(_ => IO.raiseError[Unit](new RuntimeException("query failed"))).attempt
      count  <- releases.get
    yield expect(result.isLeft) && expect(count == 2)
  }

  test("canceling session use releases the session exactly once") {
    for
      releases <- Ref.of[IO, Int](0)
      entered  <- Deferred[IO, Unit]
      measured <- instrument(Resource.make(IO.unit)(_ => releases.update(_ + 1)))
      fiber    <- measured.use(_ => entered.complete(()) *> IO.never).start
      _        <- entered.get *> fiber.cancel.timeout(2.seconds)
      count    <- releases.get
    yield expect(count == 1)
  }

  test("a failed acquisition releases resources already acquired") {
    for
      releases <- Ref.of[IO, Int](0)
      measured <- instrument(
                    Resource.make(IO.unit)(_ => releases.update(_ + 1)) *>
                      Resource.eval(IO.raiseError[Unit](new RuntimeException("acquisition failed"))),
                  )
      result <- measured.use_.attempt
      count  <- releases.get
    yield expect(result.isLeft) && expect(count == 1)
  }
