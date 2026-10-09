package org.typelevel.video.streaming.lab

import cats.effect.IO
import cats.effect.std.Env
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.{decode, parse}
import io.circe.syntax.*
import org.http4s.ember.client.EmberClientBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

private[lab] object IncidentRuntime:
  val services  = List("gateway-service", "identity-service", "catalog-service", "playback-service")
  val generator = "typelevel-video-streaming-lab-traffic"

  def service(code: String): String = code match
    case "3" => "identity-service"
    case "4" => "playback-service"
    case _ => "catalog-service"

  def profile(code: String, active: Boolean): String = code match
    case "3" => "identity"
    case "4" => "playback"
    case "5" if active => "catalog-soak"
    case _ => "catalog-courses"

  def rate(code: String): Int = code match
    case "3" => 30
    case "4" => 10
    case _ => 5

  def arguments(code: String, active: Boolean): Seq[String] =
    Seq("--profile", profile(code, active), "--rate", rate(code).toString) ++ (code match
      case "3" => Seq("--login-percent", if active then "70" else "5")
      case "4" => Seq("--modern-percent", if active then "20" else "0")
      case "5" => Seq("--request-timeout", "15s")
      case _ => Seq.empty)

  def requireThat(condition: Boolean, message: String): IO[Unit] =
    IO.raiseUnless(condition)(new IllegalStateException(message))

  /** Only recent complete intervals count; historical success and stale reports cannot pass. */
  def reportsReady(
      reports: Vector[Json],
      now: Long,
      since: Long,
      code: String,
      healthy: Boolean,
  ): Boolean =
    val recent = reports.filter(
      _.hcursor.get[Long]("timestamp_epoch_ms").exists(_ >= math.max(since, now - 70000)),
    )
    val ordered = recent.sortBy(_.hcursor.get[Long]("timestamp_epoch_ms").getOrElse(0L))
    val fresh   = ordered.lastOption.exists(
      _.hcursor.get[Long]("timestamp_epoch_ms").exists(t => t <= now && now - t <= 15000),
    )
    val window  = ordered.takeRight(if healthy then 13 else 1)
    val covered = !healthy || (window.size >= 13 &&
      window.last.hcursor.get[Long]("timestamp_epoch_ms").getOrElse(0L) -
      window.head.hcursor.get[Long]("timestamp_epoch_ms").getOrElse(0L) >= 59000)
    fresh && covered && window.nonEmpty && window.forall { report =>
      val c = report.hcursor
      val w = c.downField("window")
      c.get[String]("profile").contains(profile(code, active = !healthy)) &&
      c.get[Int]("requested_rate").contains(rate(code)) &&
      w.get[Boolean]("load_valid").contains(true) &&
      w.get[Long]("started").exists(_ >= rate(code) * 4L) &&
      (!healthy || (w.get[Long]("failed").contains(0L) && w
        .get[Long]("completed")
        .exists(_ >= rate(code) * 4L)))
    }

final private[lab] class IncidentRuntime(root: Path) extends LabIncident.Operations:
  import IncidentRuntime.*

  private def inspect(id: String, template: String): IO[String] =
    LabIo.output(root, Seq("docker", "inspect", "--format", template, id)).map(_.trim)

  def preflight: IO[Unit] =
    IO.println("[1/5] Checking environment") *>
      LabIo.logged(root, Seq("bash", "scripts/setup.sh", "--check")).timeout(45.seconds)

  def snapshot: IO[Map[String, String]] =
    services
      .traverse { name =>
        for
          id    <- LabCommands.containerId(root, name)
          stamp <- inspect(id, "{{.Id}} {{.Image}} {{.State.StartedAt}}")
        yield name -> stamp
      }
      .map(_.toMap)

  private def retryDownload(action: IO[Unit], remaining: Int = 3): IO[Unit] =
    action.handleErrorWith { error =>
      if remaining > 1 then
        IO.println("Download failed; retrying shortly. Details: .lab/commands.log") *>
          IO.sleep(3.seconds) *> retryDownload(action, remaining - 1)
      else IO.raiseError(error)
    }

  def prepare(code: String, build: Boolean, restart: Boolean): IO[Unit] =
    for
      _   <- IO.println("[2/5] Preparing workshop services (first run can take several minutes)")
      env <- LabCommands.imageEnvironment(root, followRunning = false)
      revision <- LabIo.output(root, Seq("git", "rev-parse", "HEAD"))
      pinFile   = root.resolve(".lab/exercise-images.json")
      pinned   <- LabIo.exists(pinFile)
      saved    <-
        if pinned then
          for
            data <- LabIo.read(pinFile).flatMap(value => IO.fromEither(parse(value)))
            _    <- requireThat(
                   data.hcursor.get[String]("revision").contains(revision.trim),
                   "This checkout's revision changed. Use a separate checkout for the new workshop release; existing images and edits were preserved.",
                 )
            images   <- IO.fromEither(data.hcursor.get[Map[String, String]]("images"))
            savedEnv <-
              IO.fromEither(data.hcursor.get[Option[Map[String, String]]]("image_environment"))
            _ <- requireThat(
                   savedEnv.forall(_.forall((key, value) => env.get(key).contains(value))),
                   "Image overrides differ from the saved exercise release. Use the original overrides or a separate checkout.",
                 )
            _ <-
              requireThat(images.keySet == services.toSet, "Saved exercise images are incomplete")
          yield Some(images)
        else IO.pure(None)
      // Resolve artifacts and compilation prerequisites before interrupting an existing scenario.
      _ <-
        if build && !pinned then
          LabIo.logged(root, Seq("bash", "scripts/build-images.sh"), env).timeout(20.minutes)
        else IO.unit
      _ <- retryDownload(
             LabIo.logged(root, Seq("bash", "scripts/pull-images.sh"), env).timeout(10.minutes),
           )
      warmed <- LabIo.exists(root.resolve(".lab/compiler-ready"))
      _      <-
        if warmed then IO.unit
        else
          IO.println("Preparing the Scala compiler and dependencies for participant edits") *>
            LabIo
              .logged(
                root,
                Seq(
                  "sbt",
                  "--batch",
                  "identityService/Compile/compile; playbackService/Compile/compile; catalogService/Compile/compile",
                ),
              )
              .timeout(15.minutes) *> LabIo.writeAtomic(
              root.resolve(".lab/compiler-ready"),
              revision.trim,
            )
      images <- saved.fold(
                  services
                    .traverse { name =>
                      LabIo
                        .output(
                          root,
                          Seq(
                            "docker",
                            "image",
                            "inspect",
                            "--format",
                            "{{.Id}}",
                            s"${env("IMAGE_PREFIX")}/$name:${env("IMAGE_TAG")}",
                          ),
                        )
                        .map(image => name -> image.trim)
                    }
                    .map(_.toMap),
                )(IO.pure)
      _ <-
        images.values.toList.traverse_(image =>
          LabIo.output(root, Seq("docker", "image", "inspect", "--format", "{{.Id}}", image)).void,
        )
      _ <-
        if pinned then IO.unit
        else
          LabIo.writeAtomic(
            pinFile,
            Json
              .obj(
                "revision" -> Json.fromString(revision.trim),
                "images" -> images.asJson,
                "image_environment" -> env
                  .filter((key, _) => Set("IMAGE_PREFIX", "IMAGE_TAG")(key))
                  .asJson,
              )
              .spaces2,
          )
      _       <- LabCommands.stopTraffic(root)
      running <- (services ++ List("lgtm", "postgres", "kafka", "toxiproxy")).traverse { name =>
                   LabIo
                     .probe(root, Seq("docker", "compose", "ps", "-q", name), env)
                     .map(_.exists(_.trim.nonEmpty))
                 }
      _ <-
        if running.forall(identity) && (!build || pinned) then IO.unit
        else LabIo.logged(root, Seq("bash", "scripts/start.sh"), env).timeout(15.minutes)
      // A new round starts from saved exercise images. Source files are untouched.
      _ <- LabCommands.deployImages(
             root,
             images,
             Map(
               "PLAYBACK_WORKSHOP_READ_MODE" -> (code == "4").toString,
               "CATALOG_POSTGRES_MAX_CONNECTIONS" -> (if code == "5" then "6" else "10"),
             ),
           )
      _ <- LabScenarios.resetPlatform(root)
      _ <- IO.println("[3/5] Preparing scenario data")
      _ <- if code == "4" then LabScenarios.preparePlayback(root, configure = false) else IO.unit
    yield ()

  private def trafficMatches(code: String, active: Boolean): IO[Boolean] =
    for
      running <-
        LabIo.probe(root, Seq("docker", "inspect", "--format", "{{.State.Running}}", generator))
      matches <-
        if !running.exists(_.trim == "true") then IO.pure(false)
        else
          for
            raw  <- inspect(generator, "{{json .Config.Cmd}}")
            args <- IO.fromEither(decode[Vector[String]](raw))
          yield arguments(code, active).grouped(2).forall(pair => args.sliding(2).exists(_ == pair))
    yield matches

  private def ensureTraffic(code: String, active: Boolean): IO[Unit] =
    for
      matches <- trafficMatches(code, active)
      _       <-
        if matches then IO.unit
        else
          LabCommands.stopTraffic(root) *> LabCommands.trafficStart(root, arguments(code, active))
    yield ()

  def baseline(code: String): IO[Unit] =
    IO.println("[4/5] Starting baseline traffic") *> ensureTraffic(code, active = false)

  private def reports: IO[Vector[Json]] =
    LabIo.output(root, Seq("docker", "logs", "--tail", "100", generator)).map { output =>
      output.linesIterator
        .flatMap(line => parse(line).toOption)
        .filter(_.hcursor.get[String]("type").contains("progress"))
        .toVector
    }

  private def awaitCheck(label: String, check: IO[Unit], attempts: Int = 36): IO[Unit] =
    check.handleErrorWith { error =>
      if attempts <= 1 then
        IO.raiseError(
          new IllegalStateException(
            s"$label did not become ready: ${error.getMessage}. Check incident status and .lab/commands.log; retry the same command after correcting the problem.",
            error,
          ),
        )
      else
        (if attempts % 6 == 0 then IO.println(s"Waiting for $label: ${error.getMessage}")
         else IO.unit) *>
          IO.sleep(5.seconds) *> awaitCheck(label, check, attempts - 1)
    }

  def traffic(code: String): IO[Unit] =
    ensureTraffic(code, active = true) *>
      awaitCheck(
        "traffic reports",
        for
          matched <- trafficMatches(code, active = true)
          _       <- requireThat(
                 matched,
                 "The incident workload is not running. Use incident activate to resume an interrupted activation, or incident restart for a fresh baseline",
               )
          values <- reports
          now    <- IO.realTime.map(_.toMillis)
          _      <- requireThat(
                 reportsReady(values, now, now - 15000, code, healthy = false),
                 "Waiting for a fresh valid generator interval",
               )
        yield (),
      )

  private def grafana: IO[String] =
    LabIo.output(root, Seq("docker", "compose", "port", "lgtm", "3000")).flatMap { address =>
      IO.fromOption(
        address.linesIterator.nextOption().flatMap(_.split(':').lastOption).flatMap(_.toIntOption),
      )(
        new IllegalStateException("Grafana has no published port"),
      ).map(port => s"http://localhost:$port")
    }

  def observe(code: String, since: Long, healthy: Boolean): IO[Unit] =
    (if healthy then ensureTraffic(code, active = false) else IO.unit) *>
      IO.println("[5/5] Waiting for fresh telemetry" + (if healthy then
                                                          " and a healthy 60-second traffic window"
                                                        else " from the new deployment")) *>
      EmberClientBuilder.default[IO].withTimeout(10.seconds).build.use { client =>
        awaitCheck(
          "scenario telemetry",
          for
            matched <- trafficMatches(code, active = !healthy)
            _       <- requireThat(matched, "Expected workload is not running")
            values  <- reports
            now     <- IO.realTime.map(_.toMillis)
            _       <- requireThat(
                   reportsReady(values, now, since, code, healthy),
                   "Waiting for valid recent traffic intervals",
                 )
            base <- grafana
            _    <- services.traverse_ { name =>
                   for
                     id    <- LabCommands.containerId(root, name)
                     raw   <- inspect(id, "{{json .State}}")
                     state <- IO.fromEither(parse(raw))
                     _     <- requireThat(LabPreparation.healthy(state), s"$name is not healthy")
                   yield ()
                 }
            target    = service(code)
            id       <- LabCommands.containerId(root, target)
            instance <- inspect(id, "{{.Config.Hostname}}")
            selector  =
              s"""service_name="$target",service_instance_id="$instance",http_phase="body""""
            metricName = "http_server_request_duration_seconds_count"
            updated   <- LabVerify.metric(client, base, s"max(timestamp($metricName{$selector}))")
            _         <- requireThat(
                   updated * 1000 >= math.max(since, now - 30000),
                   "Metrics have not arrived from the current service instance",
                 )
            count <- LabVerify.metric(client, base, s"sum(rate($metricName{$selector}[60s]))")
            _     <- requireThat(count > 0, "No recent requests in service metrics")
            _     <-
              if healthy then
                for
                  gatewayId       <- LabCommands.containerId(root, "gateway-service")
                  gatewayInstance <- inspect(gatewayId, "{{.Config.Hostname}}")
                  gatewaySelector  =
                    s"""service_name="gateway-service",service_instance_id="$gatewayInstance",http_phase="body""""
                  gatewayLatency <-
                    LabVerify.metric(
                      client,
                      base,
                      s"histogram_quantile(0.95,sum by (le)(rate(http_server_request_duration_seconds_bucket{$gatewaySelector}[60s])))",
                    )
                  latency <-
                    LabVerify.metric(
                      client,
                      base,
                      s"histogram_quantile(0.95,sum by (le)(rate(http_server_request_duration_seconds_bucket{$selector}[60s])))",
                    )
                  _ <- requireThat(
                         math.max(latency, gatewayLatency) < (if code == "3" then 2.0 else 0.5),
                         "Baseline latency is too high; inspect host capacity and scenario state",
                       )
                yield ()
              else IO.unit
            traces <- LabVerify.getJson(
                        client,
                        LabVerify.query(
                          base,
                          "/api/datasources/proxy/uid/tempo/api/search",
                          "q" -> s"""{resource.service.name="$target" && resource.service.instance.id="$instance"}""",
                          "start" -> (math.max(since, now - 60000) / 1000).toString,
                          "end" -> (now / 1000).toString,
                          "limit" -> "1",
                        ),
                      )
            _ <- requireThat(
                   traces.hcursor.get[Vector[Json]]("traces").exists(_.nonEmpty),
                   "No fresh trace from the current service instance",
                 )
          yield (),
        )
      }

  def activate(code: String, milliseconds: Int, verbose: Boolean): IO[Unit] =
    if code == "1" then LabScenarios.ensurePlatform(root, milliseconds, verbose)
    else ensureTraffic(code, active = true)

  def rebuild(code: String): IO[Unit] =
    IO.println(s"Building and deploying ${service(code)}; full output: .lab/commands.log") *>
      LabCommands.rebuild(root, service(code)).timeout(15.minutes)

  def source(code: String): IO[String] = IO.blocking {
    val directory = root.resolve(s"backend/services/${service(code)}/src/main/scala")
    val digest    = MessageDigest.getInstance("SHA-256")
    Using.resource(Files.walk(directory)) { paths =>
      paths
        .iterator()
        .asScala
        .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".scala"))
        .toVector
        .sortBy(_.toString)
        .foreach { path =>
          digest.update(
            directory.relativize(path).toString.getBytes(java.nio.charset.StandardCharsets.UTF_8),
          )
          digest.update(Files.readAllBytes(path))
        }
    }
    digest.digest().map(byte => f"${byte & 0xff}%02x").mkString
  }

  def ready(code: String): IO[Unit] =
    for
      base      <- grafana
      codespace <- Env[IO].get("CODESPACE_NAME")
      domain    <- Env[IO].get("GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN")
      url        = codespace.fold(base)(name =>
              s"https://$name-${base.split(':').last}.${domain.getOrElse("app.github.dev")}",
            )
      dashboard = code match
                    case "1" => "gateway-catalog-boundary"
                    case "3" => "identity-investigation"
                    case "4" => "playback-investigation"
                    case _ => "catalog-sessions"
      _ <-
        IO.println(
          s"Baseline ready. Traffic is running.\nGrafana: $url/d/$dashboard?from=now-5m&to=now\nWhen instructed: ./scripts/lab.sh incident activate $code",
        )
    yield ()
