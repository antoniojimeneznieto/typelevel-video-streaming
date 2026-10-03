package org.typelevel.video.streaming.traffic

import scala.concurrent.duration.*

import cats.effect.std.{Semaphore, Supervisor}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream

object Traffic:
  /** Each slot represents one HTTP request. Old slots are counted, never replayed in a burst. */
  final private[traffic] case class Arrival(next: Long, missed: Long, send: Boolean)

  private def slotAt(elapsed: FiniteDuration, rate: Int): Long =
    elapsed.toSeconds * rate + (elapsed.toNanos % 1000000000L) * rate / 1000000000L

  private def offset(slot: Long, rate: Int): FiniteDuration =
    (slot / rate).seconds + ((slot % rate * 1000000000L + rate - 1) / rate).nanos

  private[traffic] def arrival(next: Long, elapsed: FiniteDuration, config: Config): Arrival =
    val total = config.duration.map(d => slotAt(d - 1.nano, config.rate) + 1)
    if config.duration.exists(elapsed >= _) then
      val end = total.getOrElse(next)
      Arrival(end, (end - next).max(0L), false)
    else
      val current = slotAt(elapsed, config.rate).max(next)
      Arrival(current + 1, current - next, true)

  def run(config: Config, request: IO[RequestResult], stats: Ref[IO, Stats]): IO[Unit] =
    run(config, _ => request, stats, TrafficMetrics.noop)

  def run(
      config: Config,
      request: IO[RequestResult],
      stats: Ref[IO, Stats],
      metrics: TrafficMetrics,
  ): IO[Unit] = run(config, _ => request, stats, metrics)

  def run(
      config: Config,
      request: Long => IO[RequestResult],
      stats: Ref[IO, Stats],
      metrics: TrafficMetrics,
  ): IO[Unit] =
    Semaphore[IO](config.maxConcurrent.toLong).flatMap { permits =>
      Supervisor[IO].use { supervisor =>
        def execute(slot: Long): IO[Unit] = IO
          .uncancelable { poll =>
            IO.monotonic.flatMap { start =>
              poll(
                request(slot)
                  .timeoutTo(config.requestTimeout, IO.pure(RequestResult(None, "timeout")))
                  .handleError(_ => RequestResult(None, "request_error")),
              )
                .onCancel(
                  stats.update(s => s.copy(cancelled = s.cancelled + 1)) *> metrics.cancelled,
                )
                .flatMap(result =>
                  IO.monotonic.flatMap(end =>
                    stats.update(_.finish(result, end - start)) *>
                      metrics.completed(result, end - start),
                  ),
                )
            }
          }
          .guarantee(permits.release)

        def dispatch(a: Arrival): IO[Unit] = IO.uncancelable { _ =>
          stats.update(s =>
            s.copy(
              offered     = s.offered + a.missed + (if a.send then 1 else 0),
              droppedLate = s.droppedLate + a.missed,
            ),
          ) *> metrics.arrivals("dropped_late", a.missed) *>
            (if !a.send then IO.unit
             else
               permits.tryAcquire.flatMap {
                 case false =>
                   stats.update(s => s.copy(droppedCapacity = s.droppedCapacity + 1)) *>
                     metrics.arrivals("dropped_capacity", 1L)
                 case true =>
                   stats.update(_.start) *>
                     metrics.arrivals("sent", 1L) *>
                     metrics.started *>
                     supervisor.supervise(execute(a.next - 1)).void
               })
        }

        IO.monotonic.flatMap { start =>
          Stream
            .unfoldEval(0L) { next =>
              val target = offset(next, config.rate)
              config.duration match
                case Some(duration) if target >= duration =>
                  IO.monotonic
                    .flatMap(now => IO.sleep((start + duration - now).max(Duration.Zero)))
                    .as(None)
                case _ =>
                  for
                    now   <- IO.monotonic
                    _     <- IO.sleep((start + target - now).max(Duration.Zero))
                    awake <- IO.monotonic
                    a      = arrival(next, awake - start, config)
                    _     <- dispatch(a)
                  yield Some(() -> a.next)
            }
            .compile
            .drain *>
            // Holding all permits proves every started request has finalized its resources.
            permits.acquireN(config.maxConcurrent.toLong).timeoutTo(config.drainTimeout, IO.unit)
        }
      }
    }
