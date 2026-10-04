package org.typelevel.video.streaming.lab

import cats.effect.IO
import fs2.io.file.Files
import weaver.SimpleIOSuite

import java.util.UUID

object LabCliSuite extends SimpleIOSuite {
  test("fs2 process capture and atomic file write work in a separate directory") {
    Files[IO].tempDirectory.use { directory =>
      val root = directory.toNioPath
      val state = root.resolve("state.json")
      for {
        output <- LabIo.run(root, Seq("sh", "-c", "printf ready"), capture = true)
        missing <- LabIo.probe(root, Seq("sh", "-c", "exit 7"))
        _ <- LabIo.writeAtomic(state, "first")
        _ <- LabIo.writeAtomic(state, "second")
        contents <- LabIo.read(state)
      } yield expect(output == "ready" && missing.isEmpty && contents == "second")
    }
  }

  test("platform ledger opens and releases its fs2 file lock") {
    Files[IO].tempDirectory.use { directory =>
      val root = directory.toNioPath
      for {
        _ <- LabScenarios.platform(root, "changes", None)
        lockExists <- LabIo.exists(root.resolve(".lab/platform.lock"))
        _ <- LabScenarios.platform(root, "changes", None)
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
        List("incident", "start", "8f27"),
        List("scenario3", "baseline"),
        List("scenario4", "prepare"),
      )
      expect(commands.forall(LabCliParser.command.parse(_).isRight))
    }
  }

  test("traffic options preserve explicit values for the generator") {
    IO.pure {
      val parsed = LabCliParser.command.parse(List("traffic", "start", "--rate", "5",
        "--profile", "playback", "--modern-percent", "20"))
      expect(parsed.exists(_.action match {
        case LabAction.TrafficStart(options) => options.arguments == Seq(
          "--rate", "5", "--profile", "playback", "--modern-percent", "20")
        case _ => false
      }))
    }
  }

  test("invalid traffic options fail before Docker starts") {
    IO.pure(expect(List(
      List("traffic", "start", "--rate", "0"),
      List("traffic", "run", "--max-concurrent", "0"),
      List("traffic", "start", "--profile", "unknown"),
      List("traffic", "run", "--modern-percent", "50"),
    ).forall(LabCliParser.command.parse(_).isLeft)))
  }

  test("Decline parses nested commands and validated incident options") {
    IO.pure {
      val parsed = LabCliParser.command.parse(List(
        "--root", "/tmp/workshop", "incident", "activate", "8f27", "--milliseconds", "750",
      ))
      expect(parsed.exists(config =>
        config.root.toString == "/tmp/workshop" &&
          config.action == LabAction.Incident("8f27", Some(750))
      ))
    }
  }

  test("Decline rejects invalid incident combinations and reports help") {
    IO.pure {
      val invalidCode = LabCliParser.command.parse(List("incident", "activate", "wrong"))
      val invalidDelay = LabCliParser.command.parse(List(
        "incident", "activate", "3c91", "--milliseconds", "750",
      ))
      val help = LabCliParser.command.parse(List("--root", "/tmp/workshop", "--help"))
      expect(invalidCode.isLeft && invalidDelay.isLeft &&
        help.left.exists(_.errors.isEmpty))
    }
  }

  test("Playback seed event IDs remain compatible with the Python seeder") {
    IO.pure(expect(
      LabScenarios.playbackEventId(UUID.fromString("00000000-0000-0000-0000-000000000001")) ==
        UUID.fromString("8ef5d215-a090-5746-a73e-801b1cada4d5")
    ))
  }
}
