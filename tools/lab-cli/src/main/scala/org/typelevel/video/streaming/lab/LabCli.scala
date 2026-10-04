package org.typelevel.video.streaming.lab

import cats.effect.{ExitCode, IO}
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp

object LabCli
    extends CommandIOApp(
      name    = "lab-cli",
      header  = "Control workshop scenarios",
      version = "0.1.0-SNAPSHOT",
    ) {
  override def main: Opts[IO[ExitCode]] = LabCliParser.opts.map { config =>
    execute(config).as(ExitCode.Success).handleErrorWith { error =>
      IO.blocking(System.err.println(Option(error.getMessage).getOrElse(error.toString)))
        .as(ExitCode(1))
    }
  }

  private def execute(config: LabConfig): IO[Unit] = {
    val root = config.root
    LabIo.isRegularFile(root.resolve("scripts/lab.sh")).flatMap { exists =>
      IO.raiseUnless(exists)(new IllegalArgumentException(s"Repository root not found: $root")) *>
        (config.action match {
          case LabAction.Start(build) =>
            LabIo.run(root, Seq("bash", "scripts/start.sh") ++ Option.when(build)("--build")).void
          case LabAction.Rebuild(service) => LabCommands.rebuild(root, service)
          case LabAction.Status => LabCommands.status(root)
          case LabAction.Stop => LabCommands.stopStack(root)
          case LabAction.TrafficBuild => LabCommands.trafficBuild(root)
          case LabAction.TrafficRun(options) => LabCommands.trafficRun(root, options.arguments)
          case LabAction.TrafficStart(options) => LabCommands.trafficStart(root, options.arguments)
          case LabAction.TrafficStatus => LabCommands.trafficStatus(root)
          case LabAction.TrafficStop => LabCommands.trafficStop(root)
          case LabAction.Proxy(action, milliseconds) =>
            LabCommands.proxy(root, action, milliseconds)
          case LabAction.Changes => LabScenarios.platform(root, "changes", None)
          case LabAction.Inspect(id) => LabScenarios.platform(root, "inspect", Some(id))
          case LabAction.Rollback(id) => LabScenarios.platform(root, "rollback", Some(id))
          case LabAction.Incident("8f27", milliseconds) =>
            LabScenarios.activatePlatform(root, milliseconds.getOrElse(750))
          case LabAction.Incident("3c91", _) => LabScenarios.scenario3(root, "activate")
          case LabAction.Incident("7b42", _) => LabScenarios.scenario4(root, "activate")
          case LabAction.Scenario3(action) => LabScenarios.scenario3(root, action)
          case LabAction.Scenario4(action) => LabScenarios.scenario4(root, action)
          case LabAction.VerifyScenario1(grafana, rate, window) =>
            LabVerify.scenario1(root, grafana, rate, window)
          case LabAction.VerifyScenario3 => LabVerify.scenario3(root)
          case _ => IO.raiseError(new IllegalArgumentException("Unknown incident code"))
        })
    }
  }
}
