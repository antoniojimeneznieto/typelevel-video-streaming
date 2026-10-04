package org.typelevel.video.streaming.lab

import cats.syntax.all.*
import com.monovore.decline.{Command, Opts}

import java.nio.file.{Path, Paths}

private[lab] enum LabAction {
  case Start(build: Boolean)
  case Rebuild(service: String)
  case Status
  case Stop
  case TrafficBuild
  case TrafficRun(options: TrafficOptions)
  case TrafficStart(options: TrafficOptions)
  case TrafficStatus
  case TrafficStop
  case Proxy(action: String, milliseconds: Option[Int])
  case Changes
  case Inspect(id: String)
  case Rollback(id: String)
  case Incident(code: String, milliseconds: Option[Int])
  case Scenario3(action: String)
  case Scenario4(action: String)
  case Scenario5(action: String)
  case VerifyScenario1(grafana: String, rate: Int, window: Int)
  case VerifyScenario3
  case VerifyScenario5(grafana: String)
}

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
) {
  def arguments: Seq[String] = Seq(
    baseUrl.map("--base-url" -> _),
    rate.map(value => "--rate" -> value.toString),
    duration.map("--duration" -> _),
    maxConcurrent.map(value => "--max-concurrent" -> value.toString),
    requestTimeout.map("--request-timeout" -> _),
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

  private val trafficOptions: Opts[TrafficOptions] = (
    Opts.option[String]("base-url", help = "Gateway origin").orNone,
    Opts
      .option[Int]("rate", help = "Requests per second")
      .validate("--rate must be between 1 and 10000")(n => n >= 1 && n <= 10000)
      .orNone,
    Opts.option[String]("duration", help = "Run duration or infinite").orNone,
    Opts
      .option[Int]("max-concurrent", help = "Maximum concurrent requests")
      .validate("--max-concurrent must be positive")(_ > 0)
      .orNone,
    Opts.option[String]("request-timeout", help = "Request timeout").orNone,
    Opts.option[String]("drain-timeout", help = "Drain timeout").orNone,
    Opts.option[String]("report-interval", help = "Progress report interval").orNone,
    Opts
      .option[String]("profile", help = "Traffic profile")
      .validate("--profile must be catalog-courses, catalog-soak, identity, or playback")(
        Set("catalog-courses", "catalog-soak", "identity", "playback"),
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
    Opts.subcommand(Command("check", "Check the proxy path") { Opts(Proxy("check", None)) }) orElse
      Opts.subcommand(Command("status", "Show proxy status") {
        Opts(Proxy("status", None))
      }) orElse
      Opts.subcommand(Command("down", "Disable the proxy path") {
        Opts(Proxy("down", None))
      }) orElse
      Opts.subcommand(Command("reset", "Reset the proxy path") {
        Opts(Proxy("reset", None))
      }) orElse
      Opts.subcommand(Command("latency", "Add proxy latency") {
        Opts
          .option[Int]("milliseconds", help = "Delay in milliseconds")
          .validate("--milliseconds must be positive")(_ > 0)
          .map(value => Proxy("latency", Some(value)))
      }) orElse
      Opts.subcommand(Command("timeout", "Set proxy timeout") {
        Opts
          .option[Int]("milliseconds", help = "Timeout in milliseconds")
          .validate("--milliseconds must be positive")(_ > 0)
          .map(value => Proxy("timeout", Some(value)))
      })
  }

  private val platform = Command("platform", "Inspect and roll back local platform changes") {
    Opts.subcommand(Command("changes", "List platform changes") {
      Opts(Changes)
    }) orElse
      Opts.subcommand(Command("inspect", "Show a platform change") {
        Opts.argument[String]("CHANGE_ID").map(Inspect.apply)
      }) orElse
      Opts.subcommand(Command("rollback", "Roll back a platform change") {
        Opts.argument[String]("CHANGE_ID").map(Rollback.apply)
      })
  }

  private val incidentArgs: Opts[LabAction] =
    (
      Opts
        .argument[String]("CODE")
        .validate("CODE must be 8f27, 3c91, 7b42, or d5e0")(
          Set("8f27", "3c91", "7b42", "d5e0"),
        ),
      Opts
        .option[Int]("milliseconds", help = "Catalog delay for incident 8f27 (1–9999)")
        .validate("--milliseconds must be between 1 and 9999")(n => n >= 1 && n <= 9999)
        .orNone,
    ).mapN(Incident.apply)
      .validate("--milliseconds is only valid for incident 8f27") {
        case Incident(code, milliseconds) => code == "8f27" || milliseconds.isEmpty
        case _ => false
      }

  private val incident = Command("incident", "Activate a workshop incident") {
    Opts.subcommand(Command("start", "Start an incident using its exercise code") {
      incidentArgs
    }) orElse
      Opts.subcommand(Command("activate", "Start an incident using its exercise code") {
        incidentArgs
      })
  }

  private val scenario3 = Command("scenario3", "Control the Identity workload") {
    Opts.subcommand(Command("baseline", "Start the light login mix") {
      Opts(Scenario3("baseline"))
    }) orElse
      Opts.subcommand(Command("activate", "Start the heavy login mix") {
        Opts(Scenario3("activate"))
      }) orElse
      Opts.subcommand(Command("restore", "Stop the Identity workload") {
        Opts(Scenario3("restore"))
      })
  }

  private val scenario4 = Command("scenario4", "Control the Playback exercise") {
    Opts.subcommand(Command("prepare", "Seed Playback actors and enable workshop read mode") {
      Opts(Scenario4("prepare"))
    }) orElse
      Opts.subcommand(Command("baseline", "Start the old subject workload") {
        Opts(Scenario4("baseline"))
      }) orElse
      Opts.subcommand(Command("activate", "Start the mixed subject workload") {
        Opts(Scenario4("activate"))
      }) orElse
      Opts.subcommand(Command("restore", "Stop traffic and restore Playback mode") {
        Opts(Scenario4("restore"))
      })
  }

  private val scenario5 = Command("scenario5", "Control the Catalog session exercise") {
    Opts.subcommand(Command("prepare", "Rebuild Catalog with a small session pool") {
      Opts(Scenario5("prepare"))
    }) orElse
      Opts.subcommand(Command("rebuild", "Rebuild Catalog after a source fix") {
        Opts(Scenario5("rebuild"))
      }) orElse
      Opts.subcommand(Command("baseline", "Start healthy Catalog reads") {
        Opts(Scenario5("baseline"))
      }) orElse
      Opts.subcommand(Command("activate", "Start the mixed Catalog workload") {
        Opts(Scenario5("activate"))
      }) orElse
      Opts.subcommand(Command("restore", "Stop traffic and restore the normal pool size") {
        Opts(Scenario5("restore"))
      })
  }

  private val verify = Command("verify", "Rehearse an exercise against the running stack") {
    Opts.subcommand(Command("scenario1", "Check fault, rollback, metrics, and traces") {
      (
        Opts.option[String]("grafana", help = "Grafana URL").withDefault("http://localhost:3000"),
        Opts
          .option[Int]("rate", help = "Catalog requests per second")
          .validate("--rate must be positive")(_ > 0)
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
        Opts.help.map(_ => Changes),
    ).mapN((root, action) => LabConfig(Paths.get(root).toAbsolutePath.normalize(), action))

  val command: Command[LabConfig] = Command(
    "lab-cli",
    "Control workshop scenarios",
    helpFlag = false,
  ) {
    opts
  }
}
