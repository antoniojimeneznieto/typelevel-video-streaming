package org.typelevel.video.streaming.lab

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import fs2.io.file.Files
import io.circe.Json
import io.circe.parser.parse
import weaver.SimpleIOSuite

import java.util.UUID
import scala.concurrent.duration.*

object LabCliSuite extends SimpleIOSuite {
  test("a queued file lock can be canceled without releasing its owner") {
    Files[IO].tempDirectory.use { directory =>
      val lock = LabIo.exclusive(directory.toNioPath.resolve("state.lock"))
      for
        entered <- Ref.of[IO, Boolean](false)
        _       <- lock.use { _ =>
               Resource.make(lock.use(_ => entered.set(true)).start)(_.cancel).use { contender =>
                 IO.sleep(200.millis) *> contender.cancel.timeout(1.second)
               }
             }
        acquiredEarly <- entered.get
        _             <- lock.use(_ => entered.set(true)).timeout(1.second)
        acquiredAfter <- entered.get
      yield expect(!acquiredEarly && acquiredAfter)
    }
  }

  test("deployed scenario settings map back to Compose and invalid values fail closed") {
    IO.pure(
      expect(
        LabCommands.preservedSetting("catalog-service", "6\n") ==
          Right(Map("CATALOG_POSTGRES_MAX_CONNECTIONS" -> "6")),
      ) &&
        expect(
          LabCommands.preservedSetting("playback-service", "true\n") ==
            Right(Map("PLAYBACK_WORKSHOP_READ_MODE" -> "true")),
        ) &&
        expect(
          List("", "0", "-1", "six").forall(value =>
            LabCommands.preservedSetting("catalog-service", value).isLeft,
          ),
        ) &&
        expect(LabCommands.preservedSetting("playback-service", "").isLeft),
    )
  }

  test("preparation accepts implemented rounds and rejects unsupported ones") {
    IO.pure(expect(List(1, 3, 4, 5).forall { round =>
      LabCliParser.command
        .parse(List("prepare", round.toString))
        .exists(_.action == LabAction.Prepare(round))
    }) && expect(List("0", "2", "6", "unknown").forall { round =>
      LabCliParser.command.parse(List("prepare", round)).isLeft
    }))
  }

  test("preparation requires running containers with a passing health check") {
    def state(running: Boolean, health: String) = Json.obj(
      "Running" -> Json.fromBoolean(running),
      "Health" -> Json.obj("Status" -> Json.fromString(health)),
    )
    IO.pure(
      expect(LabPreparation.healthy(state(true, "healthy"))) &&
        expect(!LabPreparation.healthy(state(false, "healthy"))) &&
        expect(!LabPreparation.healthy(state(true, "starting"))) &&
        expect(!LabPreparation.healthy(state(true, "unhealthy"))) &&
        expect(!LabPreparation.healthy(Json.obj("Running" -> Json.True))),
    )
  }

  test("rolling back a historical change leaves newer active changes untouched") {
    Files[IO].tempDirectory.use { directory =>
      val root    = directory.toNioPath
      val changes = Json
        .arr(
          Json.obj("id" -> Json.fromString("old"), "status" -> Json.fromString("rolled_back")),
          Json.obj("id" -> Json.fromString("new"), "status" -> Json.fromString("active")),
        )
        .spaces2 + "\n"
      for {
        _ <- LabIo.createDirectories(root.resolve(".lab"))
        _ <- LabIo.writeAtomic(root.resolve(".lab/platform-changes.json"), changes)
        // No Docker/Compose project exists here: a historical rollback must not call it.
        _     <- LabScenarios.platform(root, PlatformAction.Rollback("old"))
        after <- LabIo.read(root.resolve(".lab/platform-changes.json"))
      } yield expect(after == changes)
    }
  }

  test("fs2 process capture and atomic file write work in a separate directory") {
    Files[IO].tempDirectory.use { directory =>
      val root  = directory.toNioPath
      val state = root.resolve("state.json")
      for {
        output   <- LabIo.output(root, Seq("sh", "-c", "printf ready"))
        missing  <- LabIo.probe(root, Seq("sh", "-c", "exit 7"))
        _        <- LabIo.writeAtomic(state, "first")
        _        <- LabIo.writeAtomic(state, "second")
        contents <- LabIo.read(state)
      } yield expect(output == "ready" && missing.isEmpty && contents == "second")
    }
  }

  test("platform ledger opens and releases its fs2 file lock") {
    Files[IO].tempDirectory.use { directory =>
      val root = directory.toNioPath
      for {
        _          <- LabScenarios.platform(root, PlatformAction.Changes)
        lockExists <- LabIo.exists(root.resolve(".lab/platform.lock"))
        _          <- LabScenarios.platform(root, PlatformAction.Changes)
      } yield expect(lockExists)
    }
  }

  test("lab.sh commands parse through the Scala CLI") {
    IO.pure {
      val commands = List(
        List("start", "--build"),
        List("rebuild", "playback-service"),
        List("status"),
        List("stop"),
        List("traffic", "build"),
        List("traffic", "run", "--rate", "5", "--duration", "3m"),
        List("traffic", "start", "--profile", "identity", "--rate", "30"),
        List("traffic", "status"),
        List("traffic", "stop"),
        List("proxy", "latency", "--milliseconds", "750"),
        List("proxy", "reset"),
        List("incident", "start", "1"),
        List("incident", "start", "3"),
        List("incident", "start", "4", "--build"),
        List("incident", "restart", "5"),
        List("incident", "rebuild"),
        List("incident", "status"),
        List("scenario3", "baseline"),
        List("scenario4", "prepare"),
        List("scenario5", "prepare"),
        List("scenario5", "baseline"),
        List("scenario5", "rebuild"),
        List("incident", "start", "5"),
        List("verify", "scenario1"),
        List("verify", "scenario3"),
        List("verify", "scenario5"),
      )
      expect(commands.forall(LabCliParser.command.parse(_).isRight))
    }
  }

  test("traffic options preserve explicit values for the generator") {
    IO.pure {
      val parsed = LabCliParser.command.parse(
        List("traffic", "start", "--rate", "5", "--profile", "playback", "--modern-percent", "20"),
      )
      expect(parsed.exists(_.action match {
        case LabAction.TrafficStart(options) =>
          options.arguments == Seq("--rate", "5", "--profile", "playback", "--modern-percent", "20")
        case _ => false
      }))
    }
  }

  test("invalid traffic options fail before Docker starts") {
    IO.pure(
      expect(
        List(
          List("traffic", "start", "--rate", "0"),
          List("traffic", "run", "--max-concurrent", "0"),
          List("traffic", "start", "--profile", "unknown"),
          List("traffic", "run", "--modern-percent", "50"),
          List("traffic", "start", "--setup-timeout", "nonsense"),
          List("traffic", "start", "--request-timeout", "Inf"),
          List("traffic", "start", "--duration", "0s"),
          List("traffic", "start", "--drain-timeout", "-1s"),
          List("traffic", "start", "--report-interval", "0s"),
          List("traffic", "start", "--base-url", "http://host/path"),
          List("verify", "scenario1", "--rate", "10001"),
        ).forall(LabCliParser.command.parse(_).isLeft),
      ),
    )
  }

  test("Decline parses nested commands and validated incident options") {
    IO.pure {
      val parsed = LabCliParser.command.parse(
        List(
          "--root",
          "/tmp/workshop",
          "incident",
          "activate",
          "1",
          "--milliseconds",
          "750",
        ),
      )
      expect(
        parsed.exists(config =>
          config.root.toString == "/tmp/workshop" &&
            config.action == LabAction.IncidentActivate("1", 750, false),
        ),
      )
    }
  }

  test("incident control details require an explicit facilitator flag") {
    IO.pure(
      expect(
        LabCliParser.command
          .parse(List("incident", "start", "1"))
          .exists(_.action == LabAction.IncidentStart("1", false)),
      ) &&
        expect(
          LabCliParser.command
            .parse(List("incident", "activate", "1", "--verbose"))
            .exists(_.action == LabAction.IncidentActivate("1", 750, true)),
        ) &&
        expect(LabCliParser.command.parse(List("incident", "start", "3", "--verbose")).isLeft),
    )
  }

  test("Decline rejects invalid incident combinations and reports help") {
    IO.pure {
      val invalidCode  = LabCliParser.command.parse(List("incident", "activate", "wrong"))
      val previousCode = LabCliParser.command.parse(List("incident", "start", "8f27"))
      val invalidDelay = LabCliParser.command.parse(
        List(
          "incident",
          "activate",
          "3",
          "--milliseconds",
          "750",
        ),
      )
      val help = LabCliParser.command.parse(List("--root", "/tmp/workshop", "--help"))
      expect(
        invalidCode.isLeft && previousCode.isLeft && invalidDelay.isLeft &&
          help.left.exists(_.errors.isEmpty),
      )
    }
  }

  test("Playback seed event IDs remain compatible with the Python seeder") {
    IO.pure(
      expect(
        LabScenarios.playbackEventId(UUID.fromString("00000000-0000-0000-0000-000000000001")) ==
          UUID.fromString("8ef5d215-a090-5746-a73e-801b1cada4d5"),
      ),
    )
  }

  test("rehearsal options validate windows and rates") {
    IO.pure {
      val valid = LabCliParser.command.parse(
        List(
          "verify",
          "scenario1",
          "--grafana",
          "http://localhost:3100",
          "--rate",
          "7",
          "--window",
          "45",
        ),
      )
      val invalidWindow = LabCliParser.command.parse(List("verify", "scenario1", "--window", "20"))
      val invalidRate   = LabCliParser.command.parse(List("verify", "scenario1", "--rate", "0"))
      expect(
        valid.exists(_.action == LabAction.VerifyScenario1("http://localhost:3100", 7, 45)) &&
          invalidWindow.isLeft && invalidRate.isLeft,
      )
    }
  }

  test("Scenario 1 rehearsal rejects invalid generator load and diverging rates") {
    val values = Map(
      "offered" -> 5.0,
      "sent" -> 5.0,
      "gateway_rate" -> 5.0,
      "client_rate" -> 5.0,
      "catalog_rate" -> 5.0,
    )
    val valid   = Json.obj("load_valid" -> Json.True, "failed" -> Json.fromInt(0))
    val invalid = Json.obj("load_valid" -> Json.False, "failed" -> Json.fromInt(0))
    for {
      good    <- LabVerify.checkWindow("baseline", 5, values, valid).attempt
      badLoad <- LabVerify.checkWindow("baseline", 5, values, invalid).attempt
      badRate <-
        LabVerify.checkWindow("baseline", 5, values.updated("client_rate", 2.0), valid).attempt
    } yield expect(good.isRight && badLoad.isLeft && badRate.isLeft)
  }

  test("Scenario 1 trace check requires a slow Gateway boundary and a Catalog child") {
    IO.pure {
      val trace = parse("""{
        "batches": [
          {"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"gateway-service"}}]},
           "scopeSpans":[{"spans":[{"name":"GET /courses","spanId":"client",
             "startTimeUnixNano":"1000000000","endTimeUnixNano":"1750000000"}]}]},
          {"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"catalog-service"}}]},
           "scopeSpans":[{"spans":[{"name":"GET /courses","spanId":"server",
             "parentSpanId":"client","startTimeUnixNano":"1700000000",
             "endTimeUnixNano":"1730000000"}]}]}
        ]
      }""").toOption.get
      val slow = LabVerify.matchingBoundaryTrace("trace-1", trace, slow = true).toOption.flatten
      val fast = LabVerify.matchingBoundaryTrace("trace-1", trace, slow = false).toOption.flatten
      expect(slow.exists { case (id, clientMs, serverMs) =>
        id == "trace-1" && clientMs == 750.0 && serverMs == 30.0
      } && fast.isEmpty)
    }
  }

  test("captured process results retain separate output streams and the exit code") {
    Files[IO].tempDirectory.use { directory =>
      LabIo
        .capture(
          directory.toNioPath,
          Seq("sh", "-c", "printf output; printf diagnostic >&2; exit 7"),
        )
        .map { result =>
          expect(result == LabIo.ProcessResult(7, "output", "diagnostic"))
        }
    }
  }

  test("failed logged commands retain both streams and point participants to the log") {
    Files[IO].tempDirectory.use { directory =>
      val root = directory.toNioPath
      for
        result <- LabIo
                    .logged(root, Seq("sh", "-c", "printf output; printf diagnostic >&2; exit 7"))
                    .attempt
        log <- LabIo.read(root.resolve(".lab/commands.log"))
      yield expect(log.contains("output") && log.contains("diagnostic")) &&
        expect(
          result.left.exists(error =>
            error.getMessage.contains("exit 7") && error.getMessage.contains("commands.log"),
          ),
        )
    }
  }

  test("concurrent atomic writes leave one complete value and no temporary files") {
    Files[IO].tempDirectory.use { directory =>
      val values = (0 until 20).toList.map(i => s"$i:" + ("x" * 10000))
      for
        _ <- values.parTraverse_(value =>
               LabIo.writeAtomic(directory.toNioPath.resolve("state.json"), value),
             )
        finalValue <- LabIo.read(directory.toNioPath.resolve("state.json"))
        entries    <- Files[IO].list(directory).compile.toList
      yield expect(values.contains(finalValue)) && expect(
        entries.map(_.fileName.toString) == List("state.json"),
      )
    }
  }

  test("metric decoding rejects missing, malformed, nonfinite, and ambiguous series") {
    def response(values: Json*) = Json.obj("data" -> Json.obj("result" -> Json.arr(values*)))
    def sample(value: String)   =
      Json.obj("value" -> Json.arr(Json.fromInt(1), Json.fromString(value)))
    IO.pure(
      expect.all(
        LabVerify.decodeMetric(response(sample("1.5"))) == Right(1.5),
        LabVerify.decodeMetric(response()).isLeft,
        LabVerify.decodeMetric(response(sample("1"), sample("2"))).isLeft,
        LabVerify.decodeMetric(response(sample("NaN"))).isLeft,
        LabVerify.decodeMetric(response(sample("+Inf"))).isLeft,
        LabVerify.decodeMetric(response(Json.obj("value" -> Json.arr()))).isLeft,
        LabVerify.matchingBoundaryTrace("bad", Json.obj(), false).isLeft,
      ),
    )
  }
}
