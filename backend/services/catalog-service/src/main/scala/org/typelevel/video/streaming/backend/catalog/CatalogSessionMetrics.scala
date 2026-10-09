package org.typelevel.video.streaming.backend.catalog

import scala.concurrent.duration.FiniteDuration

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import org.typelevel.otel4s.metrics.{
  BucketBoundaries,
  Histogram,
  Meter,
  MeterProvider,
  UpDownCounter,
}

/** Measures pool acquisition separately from SQL execution. */
private[catalog] object CatalogSessionMetrics:
  def instrument[A](
      sessions: Resource[IO, A],
      maxConnections: Int,
  )(using MeterProvider[IO]): Resource[IO, Resource[IO, A]] =
    for
      given Meter[IO] <-
        MeterProvider[IO].get("org.typelevel.video.streaming.catalog.sessions").toResource
      acquisition <- Meter[IO]
                       .histogram[Double]("catalog.session.acquire.duration")
                       .withUnit("s")
                       .withDescription("Time to acquire a Catalog PostgreSQL session")
                       .withExplicitBucketBoundaries(
                         BucketBoundaries(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5,
                           5.0, 10.0, 30.0),
                       )
                       .create
                       .toResource
      active <- Meter[IO]
                  .upDownCounter[Long]("catalog.session.active")
                  .withUnit("{session}")
                  .withDescription("Catalog sessions checked out from the pool")
                  .create
                  .toResource
      capacity <- Meter[IO]
                    .upDownCounter[Long]("catalog.session.capacity")
                    .withUnit("{session}")
                    .withDescription("Configured Catalog PostgreSQL session pool capacity")
                    .create
                    .toResource
      waiting <- Meter[IO]
                   .upDownCounter[Long]("catalog.session.waiting")
                   .withUnit("{request}")
                   .withDescription("Catalog requests waiting for a database session")
                   .create
                   .toResource
      nextWaitId <- Ref.of[IO, Long](0L).toResource
      waitStarts <- Ref.of[IO, Map[Long, FiniteDuration]](Map.empty).toResource
      _          <- Meter[IO]
             .observableGauge[Double]("catalog.session.wait.max_age")
             .withUnit("s")
             .withDescription("Age of the oldest request waiting for a Catalog session")
             .createWithCallback { measurement =>
               for
                 starts <- waitStarts.get
                 now    <- IO.monotonic
                 _      <- measurement.record(age(starts, now))
               yield ()
             }
      _ <- Resource.make(capacity.add(maxConnections.toLong))(_ =>
             capacity.add(-maxConnections.toLong),
           )
    yield measured(sessions, acquisition, active, waiting, nextWaitId, waitStarts)

  private def age(starts: Map[Long, FiniteDuration], now: FiniteDuration): Double =
    starts.valuesIterator.minOption.fold(0.0)(oldest => (now - oldest).toNanos.toDouble / 1e9)

  private def measured[A](
      sessions: Resource[IO, A],
      acquisition: Histogram[IO, Double],
      active: UpDownCounter[IO, Long],
      waiting: UpDownCounter[IO, Long],
      nextWaitId: Ref[IO, Long],
      waitStarts: Ref[IO, Map[Long, FiniteDuration]],
  ): Resource[IO, A] =
    def beginWait: IO[Long] =
      for
        start <- IO.monotonic
        id    <- nextWaitId.getAndUpdate(_ + 1)
        _     <- waitStarts.update(_.updated(id, start))
        _     <- waiting.inc().onError { case _ => waitStarts.update(_ - id) }
      yield id

    // Success ends the wait immediately; the Resource finalizer covers failure and cancellation.
    // Removing the ticket makes cleanup idempotent even if a metric fails.
    def endWait(id: Long): IO[Unit] =
      for
        start <- waitStarts.modify(starts => (starts - id, starts.get(id)))
        _     <- start.traverse_ { started =>
               (for
                 now <- IO.monotonic
                 _   <- acquisition.record((now - started).toNanos.toDouble / 1e9)
               yield ()).guarantee(waiting.dec())
             }
      yield ()

    for
      id <- Resource.make(beginWait)(endWait)
      // Register session ownership before executing any fallible instrumentation.
      pair <- Resource.makeFull[IO, (A, IO[Unit])](poll => poll(sessions.allocated)) {
                case (_, release) => release
              }
      _ <- Resource.eval(endWait(id))
      _ <- Resource.make(active.inc())(_ => active.dec())
    yield pair._1
