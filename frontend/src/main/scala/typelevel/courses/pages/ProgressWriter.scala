package typelevel.courses.pages

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*

/** A view owns the queue's lifetime; the application owns draining it after the view closes. */
private[pages] final class ProgressWriter private (
    state: Ref[IO, ProgressWriter.State],
    wake: Queue[IO, Unit],
    finished: Deferred[IO, Unit],
    persist: (Int, Boolean) => IO[Unit],
    report: Option[Throwable] => IO[Unit],
    runInBackground: IO[Unit] => IO[Unit]
):
  import ProgressWriter.*

  def offer(position: Int): IO[Unit] =
    state.update { current =>
      if current.closed then current
      else
        current.copy(pending =
          Some(
            Write(
              position,
              false,
              force = current.pending.exists(_.force) || current.flushes.nonEmpty
            )
          )
        )
    } *> wake.tryOffer(()).void

  /** Browser teardown cannot wait for the ordinary writer's pending HTTP response. */
  def flush(position: Int): IO[Unit] =
    IO.uncancelable { _ =>
      for
        done     <- Deferred[IO, Unit]
        accepted <- state.modify { current =>
                      if current.closed then current -> false
                      else
                        current.copy(
                          pending = Some(Write(position, true, force = true)),
                          flushes = done :: current.flushes
                        ) -> true
                    }
        _ <- IO.whenA(accepted)(
               runInBackground(
                 persist(position, true).attempt.void.guarantee(done.complete(()).void)
               ) *>
                 wake.tryOffer(()).void
             )
      yield ()
    }

  /** Idempotent and non-blocking, including when a request is currently in flight. */
  def close(position: Option[Int] = None): IO[Unit] =
    state.update { current =>
      if current.closed then current
      else
        current.copy(
          closed  = true,
          pending = position
            .map(value =>
              Write(
                value,
                true,
                force = current.pending.exists(_.force) || current.flushes.nonEmpty
              )
            )
            .orElse(current.pending.map(_.copy(keepalive = true)))
        )
    } *> wake.tryOffer(()).void

  private[pages] def awaitClosed: IO[Unit] = finished.get

  // An unload write can overlap an older request. Wait for urgent writes before
  // sending anything newer, then reassert the latest queued position.
  private def takePending: IO[State] =
    state
      .modify { current =>
        if current.flushes.nonEmpty then current.copy(flushes = Nil) -> Left(current.flushes)
        else current.copy(pending = None) -> Right(current)
      }
      .flatMap {
        case Left(flushes) => flushes.traverse_(_.get) *> takePending
        case Right(current) => IO.pure(current)
      }

  private def drain: IO[Unit] =
    wake.take *> takePending.flatMap { current =>
      current.pending.traverse_ { write =>
        IO.whenA(write.force || !current.acknowledged.contains(write.position)) {
          persist(write.position, write.keepalive).attempt.flatMap { result =>
            result.traverse_(_ => state.update(_.copy(acknowledged = Some(write.position)))) *>
              state.get.flatMap(latest => IO.unlessA(latest.closed)(report(result.swap.toOption)))
          }
        }
      } *> state.get.flatMap { latest =>
        if latest.closed && latest.pending.isEmpty then IO.unit
        else drain
      }
    }

private[pages] object ProgressWriter:
  private final case class Write(position: Int, keepalive: Boolean, force: Boolean = false)
  private final case class State(
      pending: Option[Write]            = None,
      acknowledged: Option[Int]         = None,
      flushes: List[Deferred[IO, Unit]] = Nil,
      closed: Boolean                   = false
  )

  def resource(
      persist: (Int, Boolean) => IO[Unit],
      report: Option[Throwable] => IO[Unit],
      runInBackground: IO[Unit] => IO[Unit],
      runWriter: IO[Unit] => IO[Unit]
  ): Resource[IO, ProgressWriter] =
    for
      state    <- Ref.of[IO, State](State()).toResource
      wake     <- Queue.bounded[IO, Unit](1).toResource
      finished <- Deferred[IO, Unit].toResource
      writer    = new ProgressWriter(state, wake, finished, persist, report, runInBackground)
      _        <- Resource.make(runWriter(writer.drain.guarantee(finished.complete(()).void)))(_ =>
             writer.close()
           )
    yield writer
