package typelevel.courses.pages

import scala.concurrent.duration.*

import cats.effect.std.{Queue, Supervisor}
import cats.effect.{Deferred, IO, Ref}
import munit.CatsEffectSuite

final class ProgressWriterSuite extends CatsEffectSuite:
  test("writes are sequential and intermediate queued positions coalesce") {
    Supervisor[IO].use { supervisor =>
      for
        gate    <- Deferred[IO, Unit]
        started <- Queue.unbounded[IO, Int]
        writes  <- Ref.of[IO, Vector[(Int, Boolean)]](Vector.empty)
        _       <- ProgressWriter
               .resource(
                 (position, keepalive) =>
                   writes.update(_ :+ (position -> keepalive)) *> started.offer(position) *>
                     IO.whenA(position == 10)(gate.get),
                 _ => IO.unit,
                 effect => supervisor.supervise(effect).void,
                 effect => supervisor.supervise(effect).void,
               )
               .use { writer =>
                 for
                   _       <- writer.offer(10)
                   first   <- started.take
                   _       <- writer.offer(20) *> writer.offer(30)
                   waiting <- started.tryTake
                   _       <- gate.complete(())
                   second  <- started.take
                   _       <- writer.close(Some(40))
                   _       <- writer.awaitClosed
                   actual  <- writes.get
                 yield
                   assertEquals(first, 10)
                   assertEquals(waiting, None)
                   assertEquals(second, 30)
                   assertEquals(actual, Vector(10 -> false, 30 -> false, 40 -> true))
               }
      yield ()
    }
  }

  test("a failed position can be retried and the next success clears its error") {
    Supervisor[IO].use { supervisor =>
      for
        attempts <- Ref.of[IO, Int](0)
        reports  <- Queue.unbounded[IO, Option[Throwable]]
        failure   = new RuntimeException("offline")
        _        <- ProgressWriter
               .resource(
                 (_, _) =>
                   attempts.getAndUpdate(_ + 1).flatMap { previous =>
                     if previous == 0 then IO.raiseError(failure) else IO.unit
                   },
                 reports.offer,
                 effect => supervisor.supervise(effect).void,
                 effect => supervisor.supervise(effect).void,
               )
               .use { writer =>
                 for
                   _      <- writer.offer(10)
                   failed <- reports.take
                   _      <- writer.offer(10)
                   saved  <- reports.take
                   _      <- writer.close()
                   _      <- writer.awaitClosed
                   count  <- attempts.get
                 yield
                   assertEquals(failed, Some(failure))
                   assertEquals(saved, None)
                   assertEquals(count, 2)
               }
      yield ()
    }
  }

  test(
    "close is non-blocking and preserves final completion while discarding intermediate positions",
  ) {
    Supervisor[IO].use { supervisor =>
      for
        gate      <- Deferred[IO, Unit]
        started   <- Deferred[IO, Unit]
        writes    <- Ref.of[IO, Vector[(Int, Boolean)]](Vector.empty)
        allocated <- ProgressWriter
                       .resource(
                         (position, keepalive) =>
                           writes.update(_ :+ (position -> keepalive)) *>
                             IO.whenA(position == 10)(started.complete(()).void *> gate.get),
                         _ => IO.unit,
                         effect => supervisor.supervise(effect).void,
                         effect => supervisor.supervise(effect).void,
                       )
                       .allocated
        (writer, release) = allocated
        _                <- (for
               _      <- writer.offer(10) *> started.get
               _      <- writer.offer(90)
               _      <- writer.close(Some(100)).timeout(1.second)
               _      <- writer.close(Some(30)) *> writer.offer(40)
               _      <- release.timeout(1.second)
               before <- writes.get
               _      <- gate.complete(())
               _      <- writer.awaitClosed
               after  <- writes.get
             yield
               assertEquals(before, Vector(10 -> false))
               assertEquals(after, Vector(10 -> false, 100 -> true))
             ).guarantee(gate.complete(()).void *> release)
      yield ()
    }
  }

  test("unload flush dispatches keepalive even when the same position has a pending normal write") {
    Supervisor[IO].use { supervisor =>
      for
        gate    <- Deferred[IO, Unit]
        started <- Queue.unbounded[IO, Boolean]
        _       <- ProgressWriter
               .resource(
                 (_, keepalive) => started.offer(keepalive) *> IO.unlessA(keepalive)(gate.get),
                 _ => IO.unit,
                 effect => supervisor.supervise(effect).void,
                 effect => supervisor.supervise(effect).void,
               )
               .use { writer =>
                 (for
                   _        <- writer.offer(10)
                   ordinary <- started.take
                   _        <- writer.flush(10)
                   unload   <- started.take.timeout(1.second)
                   _        <- gate.complete(()) *> writer.close()
                   _        <- writer.awaitClosed
                 yield
                   assertEquals(ordinary, false)
                   assertEquals(unload, true)
                 ).guarantee(gate.complete(()).void)
               }
      yield ()
    }
  }

  test("unload flush protects an acknowledged rewind from a different in-flight write") {
    Supervisor[IO].use { supervisor =>
      for
        gate         <- Deferred[IO, Unit]
        acknowledged <- Deferred[IO, Unit]
        started      <- Queue.unbounded[IO, (Int, Boolean)]
        _            <- ProgressWriter
               .resource(
                 (position, keepalive) =>
                   started.offer(position -> keepalive) *> IO.whenA(position == 20)(gate.get),
                 _ => acknowledged.complete(()).void,
                 effect => supervisor.supervise(effect).void,
                 effect => supervisor.supervise(effect).void,
               )
               .use { writer =>
                 (for
                   _      <- writer.offer(10) *> acknowledged.get
                   _      <- started.take
                   _      <- writer.offer(20)
                   old    <- started.take
                   _      <- writer.flush(10)
                   unload <- started.take.timeout(1.second)
                   _      <- gate.complete(())
                   latest <- started.take
                   _      <- writer.close() *> writer.awaitClosed
                 yield
                   assertEquals(old, 20 -> false)
                   assertEquals(unload, 10 -> true)
                   assertEquals(latest, 10 -> true)
                 ).guarantee(gate.complete(()).void)
               }
      yield ()
    }
  }

  test("later positions wait for an urgent flush before the serial writer persists them") {
    Supervisor[IO].use { supervisor =>
      for
        urgentGate  <- Deferred[IO, Unit]
        savedLatest <- Deferred[IO, Unit]
        started     <- Queue.unbounded[IO, (Int, Boolean)]
        persisted   <- Ref.of[IO, Vector[Int]](Vector.empty)
        _           <- ProgressWriter
               .resource(
                 (position, keepalive) =>
                   started.offer(position -> keepalive) *>
                     IO.whenA(position == 100)(urgentGate.get) *>
                     persisted.update(_ :+ position) *>
                     IO.whenA(position == 200)(savedLatest.complete(()).void),
                 _ => IO.unit,
                 effect => supervisor.supervise(effect).void,
                 effect => supervisor.supervise(effect).void,
               )
               .use { writer =>
                 (for
                   _       <- writer.flush(100)
                   urgent  <- started.take
                   _       <- writer.offer(200) *> IO.cede *> IO.cede
                   waiting <- started.tryTake
                   _       <- IO(assertEquals(waiting, None))
                   _       <- urgentGate.complete(()) *> savedLatest.get
                   latest  <- started.take
                   _       <- writer.close() *> writer.awaitClosed
                   actual  <- persisted.get
                 yield
                   assertEquals(urgent, 100 -> true)
                   assertEquals(latest, 200 -> false)
                   assertEquals(actual, Vector(100, 200))
                 ).guarantee(urgentGate.complete(()).void)
               }
      yield ()
    }
  }

  test(
    "out-of-order urgent flushes reassert the latest position even when previously acknowledged",
  ) {
    Supervisor[IO].use { supervisor =>
      for
        firstGate    <- Deferred[IO, Unit]
        secondGate   <- Deferred[IO, Unit]
        acknowledged <- Deferred[IO, Unit]
        attempts     <- Ref.of[IO, Map[Int, Int]](Map.empty)
        started      <- Queue.unbounded[IO, (Int, Int)]
        persisted    <- Queue.unbounded[IO, Int]
        _            <- ProgressWriter
               .resource(
                 (position, keepalive) =>
                   attempts
                     .modify { current =>
                       val previous = current.getOrElse(position, 0)
                       current.updated(position, previous + 1) -> previous
                     }
                     .flatMap { previous =>
                       started.offer(position -> previous) *>
                         IO.whenA(
                           keepalive && (
                             (position == 100 && previous == 0) ||
                               (position == 200 && previous == 1)
                           ),
                         )(if position == 100 then firstGate.get else secondGate.get) *>
                         persisted.offer(position)
                     },
                 _ => acknowledged.complete(()).void,
                 effect => supervisor.supervise(effect).void,
                 effect => supervisor.supervise(effect).void,
               )
               .use { writer =>
                 (for
                   _          <- writer.offer(200) *> acknowledged.get
                   _          <- started.take *> persisted.take
                   _          <- writer.flush(100)
                   first      <- started.take
                   _          <- writer.flush(200)
                   second     <- started.take
                   _          <- secondGate.complete(())
                   newer      <- persisted.take
                   _          <- IO.cede *> IO.cede
                   waiting    <- started.tryTake
                   _          <- IO(assertEquals(waiting, None))
                   _          <- firstGate.complete(())
                   older      <- persisted.take
                   repaired   <- persisted.take
                   corrective <- started.take
                   _          <- writer.close() *> writer.awaitClosed
                 yield
                   assertEquals(first, 100 -> 0)
                   assertEquals(second, 200 -> 1)
                   assertEquals(Vector(newer, older, repaired), Vector(200, 100, 200))
                   assertEquals(corrective, 200 -> 2)
                 ).guarantee(firstGate.complete(()).void *> secondGate.complete(()).void)
               }
      yield ()
    }
  }

  test("revisiting a lesson drains the old view's final position before the new writer") {
    Supervisor[IO].use { supervisor =>
      for
        gate            <- Deferred[IO, Unit]
        firstStarted    <- Deferred[IO, Unit]
        latestPersisted <- Deferred[IO, Unit]
        writes          <- Ref.of[IO, Vector[Int]](Vector.empty)
        tail            <- Ref.of[IO, IO[Unit]](IO.unit)
        runWriter        = (effect: IO[Unit]) =>
                      IO.uncancelable { _ =>
                        Deferred[IO, Unit].flatMap { done =>
                          tail.getAndSet(done.get).flatMap { previous =>
                            supervisor
                              .supervise(
                                (previous *> effect).guarantee(done.complete(()).void),
                              )
                              .void
                          }
                        }
                      }
        resource = ProgressWriter.resource(
                     (position, _) =>
                       writes.update(_ :+ position) *>
                         IO.whenA(position == 10)(firstStarted.complete(()).void *> gate.get) *>
                         IO.whenA(position == 200)(latestPersisted.complete(()).void),
                     _ => IO.unit,
                     effect => supervisor.supervise(effect).void,
                     runWriter,
                   )
        oldAllocated           <- resource.allocated
        (oldWriter, releaseOld) = oldAllocated
        _                      <- (for
               _                      <- oldWriter.offer(10) *> firstStarted.get
               _                      <- oldWriter.close(Some(140)) *> releaseOld.timeout(1.second)
               newAllocated           <- resource.allocated
               (newWriter, releaseNew) = newAllocated
               _                      <- (for
                      _      <- newWriter.offer(200) *> IO.cede *> IO.cede
                      before <- writes.get
                      _      <- IO(assertEquals(before, Vector(10)))
                      _      <- gate.complete(()) *> latestPersisted.get
                      _      <- oldWriter.awaitClosed *> newWriter.close() *> newWriter.awaitClosed
                      after  <- writes.get
                    yield assertEquals(after, Vector(10, 140, 200)))
                      .guarantee(releaseNew)
             yield ()).guarantee(gate.complete(()).void *> releaseOld)
      yield ()
    }
  }
