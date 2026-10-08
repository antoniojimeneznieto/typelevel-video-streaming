package org.typelevel.video.streaming.traffic

import scala.concurrent.duration.*

import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import weaver.SimpleIOSuite

object TrafficSuite extends SimpleIOSuite:
  private val ok = RequestResult(Some(200), Outcome.Success)

  private def run(config: Config, request: IO[RequestResult]): IO[Stats] =
    TestControl.executeEmbed(for
      stats  <- Ref.of[IO, Stats](Stats())
      _      <- Traffic.run(config, request, stats)
      result <- stats.get
    yield result)

  test("slow responses preserve arrival times and drain before returning") {
    TestControl.executeEmbed(
      for
        starts <- Ref.of[IO, Vector[FiniteDuration]](Vector.empty)
        stats  <- Ref.of[IO, Stats](Stats())
        request = IO.monotonic.flatMap(t => starts.update(_ :+ t)) *> IO.sleep(750.millis).as(ok)
        _      <- Traffic.run(Config(duration = Some(2.seconds)), request, stats)
        times  <- starts.get
        s      <- stats.get
      yield expect.all(
        times == Vector.tabulate(10)(i => (i * 200).millis),
        s.offered == 10L,
        s.completed == 10L,
        s.peakInFlight == 4L,
        s.inFlight == 0L,
        s.valid,
      ),
    )
  }

  test("saturation drops arrivals without queuing or lowering the offered rate") {
    run(Config(duration = Some(1.second), maxConcurrent = 1), IO.sleep(2.seconds).as(ok)).map { s =>
      expect.all(
        s.offered == 5L,
        s.started == 1L,
        s.completed == 1L,
        s.droppedCapacity == 4L,
        s.inFlight == 0L,
        !s.valid,
      )
    }
  }

  test("metric events account for admitted and dropped arrivals") {
    TestControl.executeEmbed(
      for
        stats  <- Ref.of[IO, Stats](Stats())
        events <- Ref.of[IO, Map[String, Long]](Map.empty)
        record  = (key: String, count: Long) =>
                   events.update(m => m.updated(key, m.getOrElse(key, 0L) + count))
        metrics = new TrafficMetrics:
                    def arrivals(result: String, count: Long): IO[Unit] = record(result, count)
                    def started: IO[Unit]                               = record("started", 1L)
                    def completed(result: RequestResult, elapsed: FiniteDuration): IO[Unit] =
                      record("completed", 1L)
                    def cancelled: IO[Unit] = record("cancelled", 1L)
        _ <- Traffic.run(
               Config(duration = Some(1.second), maxConcurrent = 1),
               IO.sleep(2.seconds).as(ok),
               stats,
               metrics,
             )
        s <- stats.get
        m <- events.get
      yield expect.all(
        m.get("sent").contains(s.started),
        m.get("dropped_capacity").contains(s.droppedCapacity),
        m.get("completed").contains(s.completed),
        m.get("started").contains(s.started),
        !m.contains("cancelled"),
        m.getOrElse("sent", 0L) + m.getOrElse("dropped_capacity", 0L) +
          m.getOrElse("dropped_late", 0L) == s.offered,
      ),
    )
  }

  test("hung requests time out, finalize, and release concurrency") {
    TestControl.executeEmbed(
      for
        released <- Ref.of[IO, Int](0)
        stats    <- Ref.of[IO, Stats](Stats())
        request   = IO.never[RequestResult].guarantee(released.update(_ + 1))
        _        <- Traffic
               .run(Config(duration = Some(1.second), requestTimeout = 300.millis), request, stats)
        s     <- stats.get
        count <- released.get
      yield expect.all(
        s.offered == 5L,
        s.completed == 5L,
        s.failed == 5L,
        s.outcomes.get("timeout").contains(5L),
        count == 5,
        s.inFlight == 0L,
        s.valid,
      ),
    )
  }

  test("timeouts retain the operation selected for each arrival") {
    TestControl.executeEmbed(
      for
        stats <- Ref.of[IO, Stats](Stats())
        _     <- Traffic.run(
               Config(rate = 2, duration = Some(1.second), requestTimeout = 100.millis),
               Workload(slot =>
                 PreparedRequest(
                   if slot == 0 then Operation.LearningPaths else Operation.Courses,
                   IO.never[RequestResult],
                 ),
               ),
               stats,
               TrafficMetrics.noop,
             )
        s <- stats.get
      yield expect.all(
        s.operations.get("catalog-learning-paths").exists(_.count == 1),
        s.operations.get("catalog-courses").exists(_.count == 1),
      ),
    )
  }

  test("a short drain deadline cancels outstanding requests and awaits their finalizers") {
    TestControl.executeEmbed(
      for
        released <- Ref.of[IO, Int](0)
        stats    <- Ref.of[IO, Stats](Stats())
        _        <- Traffic.run(
               Config(duration = Some(1.second), drainTimeout = 100.millis),
               IO.never[RequestResult].guarantee(released.update(_ + 1)),
               stats,
             )
        s     <- stats.get
        count <- released.get
      yield expect.all(
        s.started == 5L,
        s.cancelled == 5L,
        s.completed == 0L,
        s.inFlight == 0L,
        count == 5,
        !s.valid,
      ),
    )
  }

  test("cancelling continuous traffic stops workers and finalizes resources") {
    TestControl.executeEmbed(for
      released <- Ref.of[IO, Int](0)
      stats    <- Ref.of[IO, Stats](Stats())
      fiber    <- Traffic
                 .run(
                   Config(duration = None),
                   IO.never[RequestResult].guarantee(released.update(_ + 1)),
                   stats,
                 )
                 .start
      _     <- IO.sleep(950.millis)
      _     <- fiber.cancel
      s     <- stats.get
      count <- released.get
    yield expect.all(s.started == 5L, s.cancelled == 5L, s.inFlight == 0L, count == 5))
  }

  test("request errors do not stop scheduling subsequent requests") {
    run(Config(duration = Some(1.second)), IO.raiseError(new RuntimeException("private detail")))
      .map { s =>
        expect.all(
          s.offered == 5L,
          s.completed == 5L,
          s.failed == 5L,
          s.outcomes.get("request_error").contains(5L),
          !s.json("summary", 1.second).noSpaces.contains("private detail"),
        )
      }
  }

  test("rates that do not divide one second do not add an extra arrival") {
    run(Config(rate = 3, duration = Some(1.second)), IO.pure(ok)).map { s =>
      expect.all(s.offered == 3L, s.completed == 3L, s.valid)
    }
  }

  test("scheduler pauses count missed slots without catch-up bursts or requests after the window") {
    val config = Config(duration = Some(1.second))
    IO.pure(
      expect.all(
        Traffic.arrival(1L, 750.millis, config) == Traffic.Arrival(4L, 2L, true),
        Traffic.arrival(1L, 2.seconds, config) == Traffic.Arrival(5L, 4L, false),
      ),
    )
  }
