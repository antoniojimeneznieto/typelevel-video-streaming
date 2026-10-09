package org.typelevel.video.streaming.lab

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
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
      Console[IO]
        .errorln(Option(error.getMessage).getOrElse(error.toString))
        .as(ExitCode(1))
    }
  }

  private def execute(config: LabConfig): IO[Unit] = {
    val root     = config.root
    val incident = new LabIncident.Lifecycle(root, new IncidentRuntime(root))
    LabIo.isRegularFile(root.resolve("scripts/lab.sh")).flatMap { exists =>
      IO.raiseUnless(exists)(new IllegalArgumentException(s"Repository root not found: $root")) *>
        LabIo.createDirectories(root.resolve(".lab")) *>
        withLifecycleLock(config) {
          config.action match {
            case LabAction.IncidentStart(code, build, restart) =>
              incident.start(code, build, restart)
            case LabAction.IncidentActivate(code, milliseconds, verbose) =>
              incident.activate(code, milliseconds, verbose)
            case LabAction.IncidentRebuild => incident.rebuild
            case LabAction.IncidentStatus => incident.status
            case LabAction.Start(build) =>
              LabIo.run(root, Seq("bash", "scripts/start.sh") ++ Option.when(build)("--build")).void
            case LabAction.Rebuild(service) => LabCommands.rebuild(root, service)
            case LabAction.Prepare(round) => LabPreparation.prepare(root, round)
            case LabAction.Status => LabCommands.status(root)
            case LabAction.Stop => LabCommands.stopStack(root)
            case LabAction.TrafficBuild => LabCommands.trafficBuild(root)
            case LabAction.TrafficRun(options) => LabCommands.trafficRun(root, options.arguments)
            case LabAction.TrafficStart(options) =>
              LabCommands.trafficStart(root, options.arguments)
            case LabAction.TrafficStatus => LabCommands.trafficStatus(root)
            case LabAction.TrafficStop => LabCommands.trafficStop(root)
            case LabAction.Proxy(action) => LabCommands.proxy(root, action)
            case LabAction.Platform(action) => LabScenarios.platform(root, action)
            case LabAction.Scenario3(action) => LabScenarios.scenario3(root, action)
            case LabAction.Scenario4(action) => LabScenarios.scenario4(root, action)
            case LabAction.Scenario5(action) => LabScenarios.scenario5(root, action)
            case LabAction.VerifyScenario1(grafana, rate, window) =>
              LabVerify.scenario1(root, grafana, rate, window)
            case LabAction.VerifyScenario3 => LabVerify.scenario3(root)
            case LabAction.VerifyScenario5(grafana) => LabVerify.scenario5(root, grafana)
          }
        }
    }
  }

  private def withLifecycleLock(config: LabConfig)(action: IO[Unit]): IO[Unit] =
    config.action match
      case LabAction.Status | LabAction.IncidentStatus | LabAction.TrafficStatus |
          LabAction.Platform(PlatformAction.Changes) |
          LabAction.Platform(PlatformAction.Inspect(_)) =>
        action
      case _ => LabIo.exclusive(config.root.resolve(".lab/lifecycle.lock")).use(_ => action)
}
