package org.typelevel.video.streaming.lab

private[lab] enum ProxyAction:
  case Check, Status, Down, Reset
  case Latency(milliseconds: Int)
  case Timeout(milliseconds: Int)

  def arguments: Seq[String] = this match
    case Check => Seq("check")
    case Status => Seq("status")
    case Down => Seq("down")
    case Reset => Seq("reset")
    case Latency(ms) => Seq("latency", ms.toString)
    case Timeout(ms) => Seq("timeout", ms.toString)

private[lab] enum PlatformAction:
  case Changes
  case Inspect(id: String)
  case Rollback(id: String)

private[lab] enum Scenario3Action:
  case Baseline, Activate, Restore
private[lab] enum Scenario4Action:
  case Prepare, Baseline, Activate, Restore
private[lab] enum Scenario5Action:
  case Prepare, Rebuild, Baseline, Activate, Restore

private[lab] enum LabAction:
  case Start(build: Boolean)
  case Prepare(round: Int)
  case Rebuild(service: String)
  case Status, Stop, TrafficBuild, TrafficStatus, TrafficStop
  case TrafficRun(options: TrafficOptions)
  case TrafficStart(options: TrafficOptions)
  case Proxy(action: ProxyAction)
  case Platform(action: PlatformAction)
  case DelayIncident(milliseconds: Int, verbose: Boolean = false)
  case Scenario3(action: Scenario3Action)
  case Scenario4(action: Scenario4Action)
  case Scenario5(action: Scenario5Action)
  case VerifyScenario1(grafana: String, rate: Int, window: Int)
  case VerifyScenario3
  case VerifyScenario5(grafana: String)
