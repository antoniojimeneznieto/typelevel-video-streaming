package org.typelevel.video.streaming.lab

import cats.syntax.all.*
import com.monovore.decline.{Command, Opts}

import java.nio.file.{Path, Paths}
import scala.concurrent.duration.*
import scala.util.Try
import org.http4s.Uri

final private[lab] case class TrafficOptions(
    baseUrl: Option[String],
    rate: Option[Int],
    duration: Option[String],
    maxConcurrent: Option[Int],
    requestTimeout: Option[String],
    drainTimeout: Option[String],
    reportInterval: Option[String],
    profile: Option[String],
    loginPercent: Option[Int],
    modernPercent: Option[Int],
    setupTimeout: Option[String] = None,
) {
  def arguments: Seq[String] = Seq(
    baseUrl.map("--base-url" -> _),
    rate.map(value => "--rate" -> value.toString),
    duration.map("--duration" -> _),
    maxConcurrent.map(value => "--max-concurrent" -> value.toString),
    requestTimeout.map("--request-timeout" -> _),
    setupTimeout.map("--setup-timeout" -> _),
    drainTimeout.map("--drain-timeout" -> _),
    reportInterval.map("--report-interval" -> _),
    profile.map("--profile" -> _),
    loginPercent.map(value => "--login-percent" -> value.toString),
    modernPercent.map(value => "--modern-percent" -> value.toString),
  ).flatten.flatMap { case (name, value) => Seq(name, value) }
}

final private[lab] case class LabConfig(root: Path, action: LabAction)

private[lab] object LabCliParser {
  import LabAction.*

  private val applicationServices = Set(
    "status-service",
    "gateway-service",
    "identity-service",
    "catalog-service",
    "playback-service",
    "frontend",
  )

  private def positiveDuration(value: String): Boolean =
    Try(Duration(value)).toOption.exists {
      case finite: FiniteDuration => finite > Duration.Zero
      case _ => false
    }

  private def gatewayOrigin(value: String): Boolean =
    Uri
      .fromString(value)
      .exists(uri =>
        uri.scheme.exists(s => s.value == "http" || s.value == "https") &&
          uri.authority.exists(_.userInfo.isEmpty) && uri.query.isEmpty && uri.fragment.isEmpty &&
          (uri.path.isEmpty || uri.path == Uri.Path.Root),
      )

  private val trafficOptions: Opts[TrafficOptions] = (
    Opts
      .option[String]("base-url", help = "Gateway origin")
      .validate("Expected an HTTP(S) gateway origin")(gatewayOrigin)
      .orNone,
    Opts
      .option[Int]("rate", help = "Requests per second")
      .validate("--rate must be between 1 and 10000")(n => n >= 1 && n <= 10000)
      .orNone,
    Opts
      .option[String]("duration", help = "Run duration or infinite")
      .validate("Expected a positive finite duration or infinite")(v =>
        v == "infinite" || positiveDuration(v),
      )
      .orNone,
    Opts
      .option[Int]("max-concurrent", help = "Maximum concurrent requests")
      .validate("--max-concurrent must be positive")(_ > 0)
      .orNone,
    Opts
      .option[String]("request-timeout", help = "Request timeout")
      .validate("--request-timeout must be positive and finite")(positiveDuration)
      .orNone,
    Opts
      .option[String]("drain-timeout", help = "Drain timeout")
      .validate("--drain-timeout must be positive and finite")(positiveDuration)
      .orNone,
    Opts
      .option[String]("report-interval", help = "Progress report interval")
      .validate("--report-interval must be positive and finite")(positiveDuration)
      .orNone,
    Opts
      .option[String]("profile", help = "Traffic profile")
      .validate(
        "--profile must be catalog-courses, catalog-reads, catalog-soak, identity, or playback",
      )(
        Set("catalog-courses", "catalog-reads", "catalog-soak", "identity", "playback"),
      )
      .orNone,
    Opts
      .option[Int]("login-percent", help = "Identity login share")
      .validate("--login-percent must be between 0 and 100")(n => n >= 0 && n <= 100)
      .orNone,
    Opts
      .option[Int]("modern-percent", help = "Playback modern subject share")
      .validate("--modern-percent must be 0 or 20")(n => n == 0 || n == 20)
      .orNone,
    Opts
      .option[String]("setup-timeout", help = "Total actor preparation timeout")
      .validate("--setup-timeout must be positive and finite")(positiveDuration)
      .orNone,
  ).mapN(TrafficOptions.apply)

  private val traffic = Command("traffic", "Build, run, and inspect traffic") {
    Opts.subcommand(Command("build", "Build the traffic generator image") {
      Opts(TrafficBuild)
    }) orElse
      Opts.subcommand(Command("run", "Run traffic in the foreground") {
        trafficOptions.map(TrafficRun.apply)
      }) orElse
      Opts.subcommand(Command("start", "Start background traffic") {
        trafficOptions.map(TrafficStart.apply)
      }) orElse
      Opts.subcommand(Command("status", "Show background traffic reports") {
        Opts(TrafficStatus)
      }) orElse
      Opts.subcommand(Command("stop", "Stop background traffic") {
        Opts(TrafficStop)
      })
  }

  private val proxy = Command("proxy", "Control the Gateway to Catalog proxy") {
    Opts.subcommand(Command("check", "Check the proxy path") {
      Opts(Proxy(ProxyAction.Check))
    }) orElse
      Opts.subcommand(Command("status", "Show proxy status") {
        Opts(Proxy(ProxyAction.Status))
      }) orElse
      Opts.subcommand(Command("down", "Disable the proxy path") {
        Opts(Proxy(ProxyAction.Down))
      }) orElse
      Opts.subcommand(Command("reset", "Reset the proxy path") {
        Opts(Proxy(ProxyAction.Reset))
      }) orElse
      Opts.subcommand(Command("latency", "Add proxy latency") {
        Opts
          .option[Int]("milliseconds", help = "Delay in milliseconds")
          .validate("--milliseconds must be positive")(_ > 0)
          .map(value => Proxy(ProxyAction.Latency(value)))
      }) orElse
      Opts.subcommand(Command("timeout", "Set proxy timeout") {
        Opts
          .option[Int]("milliseconds", help = "Timeout in milliseconds")
          .validate("--milliseconds must be positive")(_ > 0)
          .map(value => Proxy(ProxyAction.Timeout(value)))
      })
  }

  private val platform = Command("platform", "Inspect and roll back local platform changes") {
    Opts.subcommand(Command("changes", "List platform changes") {
      Opts(Platform(PlatformAction.Changes))
    }) orElse
      Opts.subcommand(Command("inspect", "Show a platform change") {
        Opts.argument[String]("CHANGE_ID").map(id => Platform(PlatformAction.Inspect(id)))
      }) orElse
      Opts.subcommand(Command("rollback", "Roll back a platform change") {
        Opts.argument[String]("CHANGE_ID").map(id => Platform(PlatformAction.Rollback(id)))
      })
  }

  private val incidentId = Opts
    .argument[String]("ID")
    .validate("ID must be 1, 3, 4, or 5")(Set("1", "3", "4", "5"))

  private val incidentArgs: Opts[LabAction] =
    (
      Opts
        .argument[String]("ID")
        .validate("ID must be 1, 3, 4, or 5")(
          Set("1", "3", "4", "5"),
        ),
      Opts
        .option[Int]("milliseconds", help = "Catalog delay for incident 1 (1–9999)")
        .validate("--milliseconds must be between 1 and 9999")(n => n >= 1 && n <= 9999)
        .orNone,
      Opts.flag("verbose", help = "Show facilitator control details for incident 1").orFalse,
    ).tupled
      .validate("--milliseconds and --verbose are only valid for incident 1") {
        case (code, milliseconds, verbose) => code == "1" || (milliseconds.isEmpty && !verbose)
      }
      .map { case (code, milliseconds, verbose) =>
        IncidentActivate(code, milliseconds.getOrElse(750), verbose)
      }

  private val incident = Command("incident", "Prepare, activate, and rebuild a workshop incident") {
    Opts.subcommand(
      Command("start", "Prepare the scenario and wait for healthy baseline telemetry") {
        (incidentId, Opts.flag("build", help = "Build local images during setup").orFalse)
          .mapN((code, build) => IncidentStart(code, build))
      },
    ) orElse
      Opts.subcommand(Command("activate", "Activate after inspecting the healthy baseline") {
        incidentArgs
      }) orElse
      Opts.subcommand(
        Command(
          "restart",
          "Start a fresh baseline using the saved exercise images; preserve source edits",
        ) {
          incidentId.map(code => IncidentStart(code, false, true))
        },
      ) orElse
      Opts.subcommand(
        Command("rebuild", "Deploy the active scenario's source edits and check telemetry") {
          Opts(IncidentRebuild)
        },
      ) orElse
      Opts.subcommand(Command("status", "Show the recorded scenario phase") {
        Opts(IncidentStatus)
      })
  }

  private val scenario3 = Command("scenario3", "Control the Identity workload") {
    Opts.subcommand(Command("baseline", "Start the light login mix") {
      Opts(Scenario3(Scenario3Action.Baseline))
    }) orElse
      Opts.subcommand(Command("activate", "Start the heavy login mix") {
        Opts(Scenario3(Scenario3Action.Activate))
      }) orElse
      Opts.subcommand(Command("restore", "Stop the Identity workload") {
        Opts(Scenario3(Scenario3Action.Restore))
      })
  }

  private val scenario4 = Command("scenario4", "Control the Playback exercise") {
    Opts.subcommand(Command("prepare", "Seed Playback actors and enable workshop read mode") {
      Opts(Scenario4(Scenario4Action.Prepare))
    }) orElse
      Opts.subcommand(Command("baseline", "Start the old subject workload") {
        Opts(Scenario4(Scenario4Action.Baseline))
      }) orElse
      Opts.subcommand(Command("activate", "Start the mixed subject workload") {
        Opts(Scenario4(Scenario4Action.Activate))
      }) orElse
      Opts.subcommand(Command("restore", "Stop traffic and restore Playback mode") {
        Opts(Scenario4(Scenario4Action.Restore))
      })
  }

  private val scenario5 = Command("scenario5", "Control the Catalog session exercise") {
    Opts.subcommand(Command("prepare", "Configure Catalog with a small session pool") {
      Opts(Scenario5(Scenario5Action.Prepare))
    }) orElse
      Opts.subcommand(Command("rebuild", "Rebuild Catalog after a source fix") {
        Opts(Scenario5(Scenario5Action.Rebuild))
      }) orElse
      Opts.subcommand(Command("baseline", "Start healthy Catalog reads") {
        Opts(Scenario5(Scenario5Action.Baseline))
      }) orElse
      Opts.subcommand(Command("activate", "Start the mixed Catalog workload") {
        Opts(Scenario5(Scenario5Action.Activate))
      }) orElse
      Opts.subcommand(Command("restore", "Stop traffic and restore the normal pool size") {
        Opts(Scenario5(Scenario5Action.Restore))
      })
  }

  private val verify = Command("verify", "Rehearse an exercise against the running stack") {
    Opts.subcommand(Command("scenario1", "Check fault, rollback, metrics, and traces") {
      (
        Opts.option[String]("grafana", help = "Grafana URL").withDefault("http://localhost:3000"),
        Opts
          .option[Int]("rate", help = "Catalog requests per second")
          .validate("--rate must be between 1 and 10000")(n => n > 0 && n <= 10000)
          .withDefault(5),
        Opts
          .option[Int]("window", help = "Observation window in seconds")
          .validate("--window must be at least 35 seconds")(_ >= 35)
          .withDefault(40),
      ).mapN(VerifyScenario1.apply)
    }) orElse
      Opts.subcommand(Command("scenario3", "Check light and heavy Identity mixes") {
        Opts(VerifyScenario3)
      }) orElse
      Opts.subcommand(Command("scenario5", "Check Catalog pool depletion") {
        Opts
          .option[String]("grafana", help = "Grafana URL")
          .withDefault("http://localhost:3000")
          .map(VerifyScenario5.apply)
      })
  }

  val opts: Opts[LabConfig] =
    (
      Opts
        .option[String]("root", help = "Repository checkout containing scripts/lab.sh")
        .withDefault("."),
      Opts.subcommand(Command("start", "Start the workshop stack") {
        Opts.flag("build", help = "Build local images before starting").orFalse.map(Start.apply)
      }) orElse
        Opts.subcommand(
          Command("prepare", "Reset transient state before a controlled workshop round") {
            Opts
              .argument[Int]("ROUND")
              .validate("ROUND must be 1, 3, 4, or 5; round 2 is not implemented")(Set(1, 3, 4, 5))
              .map(Prepare.apply)
          },
        ) orElse
        Opts.subcommand(Command("rebuild", "Rebuild and restart one application service") {
          Opts
            .argument[String]("SERVICE")
            .validate("Expected an application service")(applicationServices)
            .map(Rebuild.apply)
        }) orElse
        Opts.subcommand(Command("status", "Show stack status") { Opts(Status) }) orElse
        Opts.subcommand(Command("stop", "Stop the stack") { Opts(Stop) }) orElse
        Opts.subcommand(traffic) orElse
        Opts.subcommand(proxy) orElse
        Opts.subcommand(platform) orElse
        Opts.subcommand(incident) orElse
        Opts.subcommand(scenario3) orElse
        Opts.subcommand(scenario4) orElse
        Opts.subcommand(scenario5) orElse
        Opts.subcommand(verify) orElse
        Opts.help.map(_ => Platform(PlatformAction.Changes)),
    ).mapN((root, action) => LabConfig(Paths.get(root).toAbsolutePath.normalize(), action))

  val command: Command[LabConfig] = Command(
    "lab-cli",
    "Control workshop scenarios",
    helpFlag = false,
  ) {
    opts
  }
}
