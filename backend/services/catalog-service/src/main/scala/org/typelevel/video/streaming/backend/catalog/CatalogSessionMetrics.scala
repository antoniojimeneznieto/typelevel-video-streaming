package org.typelevel.video.streaming.backend.catalog

import scala.concurrent.duration.FiniteDuration

import cats.effect.{IO, Ref, Resource}
import org.typelevel.otel4s.metrics.{
  BucketBoundaries,
  Gauge,
  Histogram,
  MeterProvider,
  UpDownCounter,
}

/** Measures pool acquisition separately from SQL execution. */
private[catalog] object CatalogSessionMetrics:
  def instrument[A](
      sessions: Resource[IO, A],
      provider: MeterProvider[IO],
      maxConnections: Int,
  ): IO[Resource[IO, A]] =
    for
      meter       <- provider.get("org.typelevel.video.streaming.catalog.sessions")
      acquisition <- meter
                       .histogram[Double]("catalog.session.acquire.duration")
                       .withUnit("s")
                       .withDescription("Time to acquire a Catalog PostgreSQL session")
                       .withExplicitBucketBoundaries(
                         BucketBoundaries(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5,
                           5.0, 10.0, 30.0),
                       )
                       .create
      active <- meter
                  .upDownCounter[Long]("catalog.session.active")
                  .withUnit("{session}")
                  .withDescription("Catalog sessions checked out from the pool")
                  .create
      capacity <- meter
                    .upDownCounter[Long]("catalog.session.capacity")
                    .withUnit("{session}")
                    .withDescription("Configured Catalog PostgreSQL session pool capacity")
                    .create
      waiting <- meter
                   .upDownCounter[Long]("catalog.session.waiting")
                   .withUnit("{request}")
                   .withDescription("Catalog requests waiting for a database session")
                   .create
      oldestWait <- meter
                      .gauge[Double]("catalog.session.wait.max_age")
                      .withUnit("s")
                      .withDescription("Age of the oldest request waiting for a Catalog session")
                      .create
      nextWaitId <- Ref.of[IO, Long](0L)
      waitStarts <- Ref.of[IO, Map[Long, FiniteDuration]](Map.empty)
      _          <- capacity.add(maxConnections.toLong)
      _          <- oldestWait.record(0.0)
    yield measured(sessions, acquisition, active, waiting, oldestWait, nextWaitId, waitStarts)

  private def age(starts: Map[Long, FiniteDuration], now: FiniteDuration): Double =
    starts.valuesIterator.minOption.fold(0.0)(oldest => (now - oldest).toNanos.toDouble / 1e9)

  private def measured[A](
      sessions: Resource[IO, A],
      acquisition: Histogram[IO, Double],
      active: UpDownCounter[IO, Long],
      waiting: UpDownCounter[IO, Long],
      oldestWait: Gauge[IO, Double],
      nextWaitId: Ref[IO, Long],
      waitStarts: Ref[IO, Map[Long, FiniteDuration]],
  ): Resource[IO, A] =
    Resource
      .makeFull[IO, (A, IO[Unit])] { poll =>
        for
          start  <- IO.monotonic
          id     <- nextWaitId.getAndUpdate(_ + 1)
          starts <- waitStarts.updateAndGet(_.updated(id, start))
          _      <- oldestWait.record(age(starts, start))
          _      <- waiting.inc()
          // Keep bookkeeping and ownership transfer masked, but preserve the pool's
          // cancelable acquisition while a request waits for an available session.
          pair <- poll(sessions.allocated).guarantee(
                    IO.monotonic.flatMap { end =>
                      waitStarts.updateAndGet(_ - id).flatMap { remaining =>
                        oldestWait.record(age(remaining, end))
                      } *> acquisition.record((end - start).toNanos.toDouble / 1e9)
                    } *> waiting.dec(),
                  )
          _ <- active.inc()
        yield pair
      } { case (_, release) =>
        release.guarantee(active.dec())
      }
      .map(_._1)
