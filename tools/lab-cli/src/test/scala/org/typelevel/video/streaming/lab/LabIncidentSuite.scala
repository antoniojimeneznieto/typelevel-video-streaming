package org.typelevel.video.streaming.lab

import cats.effect.{IO, Ref}
import fs2.io.file.Files
import io.circe.Json
import weaver.SimpleIOSuite

object LabIncidentSuite extends SimpleIOSuite:
  test("existing incident state loads with the numeric ID") {
    Files[IO].tempDirectory.use { directory =>
      val root = directory.toNioPath
      for
        _ <- LabIo.createDirectories(root.resolve(".lab"))
        _ <- LabIo.writeAtomic(
               root.resolve(".lab/incident.json"),
               """{"code":"7b42","phase":"Ready","since":1,"deployment":{},"milliseconds":750}""",
             )
        state <- LabIncident.load(root)
      yield expect(state.exists(_.code == "4"))
    }
  }

  final private case class World(
      prepares: Int            = 0,
      baselines: Int           = 0,
      activations: Int         = 0,
      builds: Int              = 0,
      ready: Boolean           = true,
      buildFails: Boolean      = false,
      activationFails: Boolean = false,
      deployed: String         = "original",
      source: String           = "exercise",
  )

  private def fixture[A](test: (LabIncident.Lifecycle, Ref[IO, World]) => IO[A]): IO[A] =
    Files[IO].tempDirectory.use { directory =>
      val root = directory.toNioPath
      for
        _     <- LabIo.createDirectories(root.resolve(".lab"))
        world <- Ref.of[IO, World](World())
        ops    = new LabIncident.Operations:
                def preflight                                               = IO.unit
                def prepare(code: String, build: Boolean, restart: Boolean) =
                  world.update(w => w.copy(prepares = w.prepares + 1, deployed = "original"))
                def baseline(code: String) = world.update(w => w.copy(baselines = w.baselines + 1))
                def snapshot               = world.get.map(w => Map("service" -> w.deployed))
                def observe(code: String, since: Long, healthy: Boolean) = world.get.flatMap(w =>
                  IO.raiseUnless(w.ready)(new IllegalStateException("telemetry unavailable")),
                )
                def activate(code: String, milliseconds: Int, verbose: Boolean) =
                  world.get.flatMap { w =>
                    // Like production, reconciliation leaves an already-applied change in place.
                    world.update(_.copy(activations = 1)) *>
                      IO.raiseWhen(w.activationFails)(
                        new IllegalStateException("activation interrupted"),
                      )
                  }
                def traffic(code: String) = IO.unit
                def rebuild(code: String) = for
                  w <- world.get
                  _ <- world.update(s => s.copy(builds = s.builds + 1))
                  _ <- IO.raiseWhen(w.buildFails)(new IllegalStateException("compilation failed"))
                  _ <- world.update(_.copy(deployed = "edited"))
                yield ()
                def source(code: String) = world.get.map(_.source)
                def ready(code: String)  = IO.unit
        value <- test(new LabIncident.Lifecycle(root, ops), world)
      yield value
    }

  test("setup and activation are separate and repeated commands preserve the running scenario") {
    fixture { (lab, world) =>
      for
        _        <- lab.start("4", false, false)
        _        <- lab.start("4", false, false)
        baseline <- world.get
        _        <- lab.activate("4", 750, false)
        _        <- lab.activate("4", 750, false)
        _        <- lab.start("4", false, false)
        active   <- world.get
      yield expect(
        baseline.prepares == 1 && baseline.baselines == 1 && baseline.activations == 0,
      ) &&
        expect(active.prepares == 1 && active.activations == 1)
    }
  }

  test("telemetry failure resumes observation without recreating services or traffic") {
    fixture { (lab, world) =>
      for
        _          <- world.update(_.copy(ready = false))
        failed     <- lab.start("4", false, false).attempt
        activation <- lab.activate("4", 750, false).attempt
        _          <- world.update(_.copy(ready = true))
        _          <- lab.start("4", false, false)
        result     <- world.get
      yield expect(
        failed.isLeft && activation.isLeft && result.prepares == 1 && result.baselines == 1 && result.activations == 0,
      )
    }
  }

  test("activation rechecks readiness and rejects a different scenario") {
    fixture { (lab, world) =>
      for
        missing <- lab.activate("4", 750, false).attempt
        _       <- lab.start("4", false, false)
        wrong   <- lab.activate("3", 750, false).attempt
        _       <- world.update(_.copy(ready = false))
        stale   <- lab.activate("4", 750, false).attempt
        result  <- world.get
      yield expect(missing.isLeft && wrong.isLeft && stale.isLeft && result.activations == 0)
    }
  }

  test("an interrupted activation is reconciled with its original options") {
    fixture { (lab, world) =>
      for
        _           <- lab.start("1", false, false)
        _           <- world.update(_.copy(activationFails = true))
        interrupted <- lab.activate("1", 750, false).attempt
        start       <- lab.start("1", false, false).attempt
        changed     <- lab.activate("1", 900, false).attempt
        _           <- world.update(_.copy(activationFails = false))
        _           <- lab.activate("1", 750, false)
        result      <- world.get
      yield expect(interrupted.isLeft && start.isLeft && changed.isLeft && result.activations == 1)
    }
  }

  test("a failed build can be retried and does not recreate baseline traffic") {
    fixture { (lab, world) =>
      for
        _      <- lab.start("4", false, false)
        _      <- lab.activate("4", 750, false)
        _      <- world.update(_.copy(buildFails = true))
        failed <- lab.rebuild.attempt
        _      <- world.update(_.copy(buildFails = false))
        _      <- lab.rebuild
        result <- world.get
      yield expect(
        failed.isLeft && result.builds == 2 && result.baselines == 1 && result.activations == 1,
      )
    }
  }

  test("a deployed edit waits for telemetry on retry without compiling twice") {
    fixture { (lab, world) =>
      for
        _      <- lab.start("4", false, false)
        _      <- lab.activate("4", 750, false)
        _      <- world.update(_.copy(ready = false))
        failed <- lab.rebuild.attempt
        _      <- world.update(_.copy(ready = true))
        _      <- lab.rebuild
        result <- world.get
      yield expect(failed.isLeft && result.builds == 1 && result.deployed == "edited")
    }
  }

  test("an edit after a telemetry failure is rebuilt on retry") {
    fixture { (lab, world) =>
      for
        _      <- lab.start("4", false, false)
        _      <- lab.activate("4", 750, false)
        _      <- world.update(_.copy(ready = false))
        failed <- lab.rebuild.attempt
        _      <- world.update(_.copy(source = "revised", ready = true))
        _      <- lab.rebuild
        result <- world.get
      yield expect(failed.isLeft && result.builds == 2 && result.deployed == "edited")
    }
  }

  test("source fingerprint changes with service Scala edits") {
    Files[IO].tempDirectory.use { directory =>
      val root   = directory.toNioPath
      val source = root.resolve("backend/services/playback-service/src/main/scala/Telemetry.scala")
      for
        _      <- LabIo.createDirectories(source.getParent)
        _      <- LabIo.writeAtomic(source, "object Telemetry")
        first  <- new IncidentRuntime(root).source("4")
        _      <- LabIo.writeAtomic(source, "object Telemetry { val changed = true }")
        second <- new IncidentRuntime(root).source("4")
      yield expect(first.nonEmpty && first != second)
    }
  }

  test(
    "external deployment changes require explicit action and restart establishes a new baseline",
  ) {
    fixture { (lab, world) =>
      for
        _      <- lab.start("4", false, false)
        _      <- world.update(_.copy(deployed = "external"))
        failed <- lab.start("4", false, false).attempt
        _      <- lab.start("4", false, true)
        result <- world.get
      yield expect(failed.isLeft && result.prepares == 2 && result.baselines == 2)
    }
  }

  test("switching scenarios creates a fresh baseline without activating") {
    fixture { (lab, world) =>
      for
        _      <- lab.start("4", false, false)
        _      <- lab.activate("4", 750, false)
        _      <- lab.start("5", false, false)
        result <- world.get
      yield expect(result.prepares == 2 && result.baselines == 2 && result.activations == 1)
    }
  }

  test("baseline readiness rejects stale, sparse, invalid, wrong-profile, and failing reports") {
    val now     = 200000L
    val reports = (0 to 12).toVector.map { n =>
      Json.obj(
        "type" -> Json.fromString("progress"),
        "profile" -> Json.fromString("playback"),
        "requested_rate" -> Json.fromInt(10),
        "timestamp_epoch_ms" -> Json.fromLong(now - 60000 + n * 5000),
        "window" -> Json.obj(
          "load_valid" -> Json.True,
          "started" -> Json.fromInt(50),
          "completed" -> Json.fromInt(50),
          "failed" -> Json.fromInt(0),
        ),
      )
    }
    def ready(
        values: Vector[Json],
        time: Long       = now,
        code: String     = "4",
        healthy: Boolean = true,
    ) =
      IncidentRuntime.reportsReady(values, time, 100000L, code, healthy)
    val failing =
      reports.map(_.deepMerge(Json.obj("window" -> Json.obj("failed" -> Json.fromInt(10)))))
    val invalid =
      reports.map(_.deepMerge(Json.obj("window" -> Json.obj("load_valid" -> Json.False))))
    IO.pure(
      expect.all(
        ready(reports),
        !ready(reports, now + 20000),
        !ready(reports.takeRight(2)),
        !ready(failing),
        !ready(invalid),
        !ready(reports, code = "3"),
        ready(failing, healthy = false),
        !ready(invalid, healthy = false),
      ),
    )
  }
