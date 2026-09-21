package typelevel.courses.pages

import scala.concurrent.duration.*

import cats.effect.std.{Queue, Supervisor}
import cats.effect.{Deferred, IO, Ref, Resource}
import munit.CatsEffectSuite

final class ProgressWriterSuite extends CatsEffectSuite:
  test("queued positions coalesce and closing saves the final position") {
    for
      gate    <- Deferred[IO, Unit]
      started <- Queue.unbounded[IO, Int]
      writes  <- Ref.of[IO, Vector[(Int, Boolean)]](Vector.empty)
      _       <- writerResource { (position, keepalive) =>
             started.offer(position) *> IO.whenA(position == 10)(gate.get) *>
               writes.update(_ :+ (position -> keepalive))
           }.use { writer =>
             (for
               _       <- writer.offer(10)
               first   <- started.take
               _       <- writer.offer(20) *> writer.offer(30)
               waiting <- started.tryTake
               _       <- gate.complete(())
               second  <- started.take
               _       <- writer.close(Some(40)) *> writer.awaitClosed
               actual  <- writes.get
             yield
               assertEquals(first, 10)
               assertEquals(waiting, None)
               assertEquals(second, 30)
               assertEquals(actual, Vector(10 -> false, 30 -> false, 40 -> true))
             ).guarantee(gate.complete(()).void)
           }
    yield ()
  }

  test("retrying a failed save clears its error") {
    for
      attempts <- Ref.of[IO, Int](0)
      reports  <- Queue.unbounded[IO, Option[Throwable]]
      failure   = new RuntimeException("offline")
      _        <- writerResource(
             (_, _) =>
               attempts.getAndUpdate(_ + 1).flatMap { previous =>
                 if previous == 0 then IO.raiseError(failure) else IO.unit
               },
             reports.offer,
           ).use { writer =>
             for
               _      <- writer.offer(10)
               failed <- reports.take
               _      <- writer.offer(10)
               saved  <- reports.take
               _      <- writer.close() *> writer.awaitClosed
               count  <- attempts.get
             yield
               assertEquals(failed, Some(failure))
               assertEquals(saved, None)
               assertEquals(count, 2)
           }
    yield ()
  }

  test("unloading sends keepalive even while an ordinary save is pending") {
    for
      gate    <- Deferred[IO, Unit]
      started <- Queue.unbounded[IO, (Int, Boolean)]
      _       <- writerResource { (position, keepalive) =>
             started.offer(position -> keepalive) *> IO.unlessA(keepalive)(gate.get)
           }.use { writer =>
             (for
               _        <- writer.offer(10)
               ordinary <- started.take
               _        <- writer.flush(20)
               unload   <- started.take.timeout(1.second)
               _        <- gate.complete(()) *> writer.close() *> writer.awaitClosed
             yield
               assertEquals(ordinary, 10 -> false)
               assertEquals(unload, 20 -> true)
             ).guarantee(gate.complete(()).void)
           }
    yield ()
  }

  private def writerResource(
      persist: (Int, Boolean) => IO[Unit],
      report: Option[Throwable] => IO[Unit] = _ => IO.unit,
  ): Resource[IO, ProgressWriter] =
    Supervisor[IO].flatMap { supervisor =>
      val start = (effect: IO[Unit]) => supervisor.supervise(effect).void
      ProgressWriter.resource(persist, report, start, start)
    }
