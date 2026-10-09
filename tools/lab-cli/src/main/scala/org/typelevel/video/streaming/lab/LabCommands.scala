package org.typelevel.video.streaming.lab

import cats.effect.{IO, Resource}
import cats.effect.std.Env
import cats.syntax.all.*

import java.nio.file.Path

private[lab] object LabCommands {
  private val container = "typelevel-video-streaming-lab-traffic"
  private val gateway   = "http://gateway-service:8084"

  private[lab] def imageEnvironment(root: Path, followRunning: Boolean): IO[Map[String, String]] = {
    val detected = (Env[IO].get("IMAGE_PREFIX"), Env[IO].get("IMAGE_TAG")).tupled.flatMap {
      (prefix, tag) =>
        if followRunning && prefix.isEmpty && tag.isEmpty then
          LabIo
            .probe(root, Seq("docker", "compose", "ps", "-q", "gateway-service"))
            .flatMap {
              case Some(id) if id.trim.nonEmpty =>
                LabIo.probe(
                  root,
                  Seq("docker", "inspect", "--format", "{{.Config.Image}}", id.trim),
                )
              case _ => IO.pure(None)
            }
            .map(_.flatMap { value =>
              val image    = value.trim
              val marker   = "/gateway-service:"
              val position = image.lastIndexOf(marker)
              Option.when(position > 0)(
                Map(
                  "IMAGE_PREFIX" -> image.take(position),
                  "IMAGE_TAG" -> image.drop(position + marker.length),
                ),
              )
            }.getOrElse(Map.empty[String, String]))
        else IO.pure(Map.empty[String, String])
    }

    val resolved = for
      environment <- detected
      file         = root.resolve(".lab/exercise-images.json")
      exists      <- LabIo.exists(file)
      saved       <-
        if exists then
          LabIo.read(file).flatMap(value => IO.fromEither(io.circe.parser.parse(value))).flatMap {
            json =>
              IO.fromEither(json.hcursor.get[Option[Map[String, String]]]("image_environment"))
                .map(
                  _.getOrElse(Map.empty).filter((key, _) => Set("IMAGE_PREFIX", "IMAGE_TAG")(key)),
                )
          }
        else IO.pure(Map.empty[String, String])
      prefix <- Env[IO].get("IMAGE_PREFIX")
      tag    <- Env[IO].get("IMAGE_TAG")
    yield environment ++ saved ++ prefix.map("IMAGE_PREFIX" -> _) ++ tag.map("IMAGE_TAG" -> _)

    resolved.flatMap { environment =>
      val script = """set -euo pipefail
project_directory="$PWD"
source scripts/images.sh
printf 'IMAGE_PREFIX=%s\nIMAGE_TAG=%s\nLOCAL_UID=%s\nLOCAL_GID=%s\nS3_PUBLIC_ENDPOINT=%s\n' \
  "$IMAGE_PREFIX" "$IMAGE_TAG" "$LOCAL_UID" "$LOCAL_GID" "${S3_PUBLIC_ENDPOINT:-}"
"""
      LabIo.output(root, Seq("bash", "-c", script), environment).map { output =>
        output.linesIterator.flatMap { line =>
          line.split("=", 2) match {
            case Array(key, value) if value.nonEmpty => Some(key -> value)
            case _ => None
          }
        }.toMap
      }
    }
  }

  private def withImages(root: Path, followRunning: Boolean)(
      use: Map[String, String] => IO[Unit],
  ): IO[Unit] = imageEnvironment(root, followRunning).flatMap(use)

  def status(root: Path): IO[Unit] = withImages(root, false) { env =>
    LabIo.run(root, Seq("docker", "compose", "ps"), env).void
  }

  private[lab] def scenarioSetting(service: String): Option[(String, String)] = service match {
    case "catalog-service" => Some("POSTGRES_MAX_CONNECTIONS" -> "CATALOG_POSTGRES_MAX_CONNECTIONS")
    case "playback-service" => Some("WORKSHOP_READ_MODE" -> "PLAYBACK_WORKSHOP_READ_MODE")
    case _ => None
  }

  private[lab] def preservedSetting(
      service: String,
      value: String,
  ): Either[String, Map[String, String]] =
    scenarioSetting(service).toRight(s"No scenario setting for $service").flatMap {
      case (_, composeKey) =>
        val setting = value.trim
        val valid   = service match {
          case "catalog-service" => setting.toIntOption.exists(_ > 0)
          case "playback-service" => Set("true", "false")(setting)
          case _ => false
        }
        Either.cond(
          valid,
          Map(composeKey -> setting),
          s"Invalid or missing deployed setting for $service",
        )
    }

  private def deployedSettings(root: Path, service: String): IO[Map[String, String]] =
    scenarioSetting(service).fold(IO.pure(Map.empty[String, String])) { case (containerKey, _) =>
      LabIo
        .output(root, Seq("docker", "compose", "ps", "--all", "-q", service))
        .map(_.trim)
        .flatMap { id =>
          if id.isEmpty then IO.pure(Map.empty[String, String])
          else if id.contains('\n') then
            IO.raiseError(new IllegalStateException(s"Expected one $service container"))
          else {
            // Select only this non-secret setting inside Docker, never dump Config.Env.
            val template =
              s"""{{range .Config.Env}}{{if eq (index (split . "=") 0) "$containerKey"}}{{index (split . "=") 1}}{{end}}{{end}}"""
            LabIo
              .output(root, Seq("docker", "inspect", "--format", template, id))
              .flatMap(value =>
                IO.fromEither(
                  preservedSetting(service, value).left.map(new IllegalStateException(_)),
                ),
              )
          }
        }
    }

  def rebuild(root: Path, service: String, extra: Map[String, String] = Map.empty): IO[Unit] =
    for
      env        <- imageEnvironment(root, true)
      settings   <- deployedSettings(root, service)
      environment = env ++ settings ++ extra
      _          <- LabIo.logged(root, Seq("bash", "scripts/build-images.sh", service), environment)
      _          <- LabIo.logged(
             root,
             Seq(
               "docker",
               "compose",
               "up",
               "--detach",
               "--no-deps",
               "--no-build",
               "--pull",
               "never",
               "--force-recreate",
               "--wait",
               service,
             ),
             environment,
           )
    yield ()

  /** Configuration changes use the running immutable image, including participant repairs. */
  def configure(root: Path, service: String, settings: Map[String, String]): IO[Unit] =
    for
      id    <- containerId(root, service)
      image <- LabIo.output(root, Seq("docker", "inspect", "--format", "{{.Image}}", id))
      _     <- deployImages(root, Map(service -> image.trim), settings)
    yield ()

  def deployImages(
      root: Path,
      images: Map[String, String],
      settings: Map[String, String],
  ): IO[Unit] =
    for
      env         <- imageEnvironment(root, true)
      _           <- LabIo.createDirectories(root.resolve(".lab"))
      overrideFile = root.resolve(".lab/incident-images.json")
      _           <- LabIo.writeAtomic(
             overrideFile,
             io.circe.Json
               .obj(
                 "services" -> io.circe.Json.obj(
                   images.toSeq.map { (service, image) =>
                     service -> io.circe.Json.obj("image" -> io.circe.Json.fromString(image))
                   }*,
                 ),
               )
               .spaces2,
           )
      _ <- LabIo.logged(
             root,
             Seq(
               "docker",
               "compose",
               "-f",
               "compose.yaml",
               "-f",
               overrideFile.toString,
               "up",
               "--detach",
               "--no-deps",
               "--no-build",
               "--pull",
               "never",
               "--force-recreate",
               "--wait",
               "--wait-timeout",
               "120",
             ) ++ images.keys.toSeq.sorted,
             env ++ settings,
           )
    yield ()

  def rebuilt(root: Path, service: String, extra: Map[String, String]): Resource[IO, Unit] =
    Resource.eval(deployedSettings(root, service)).flatMap { previous =>
      Resource
        .make(IO.unit)(_ => rebuild(root, service, previous))
        .evalMap(_ => rebuild(root, service, extra))
    }

  def stopStack(root: Path): IO[Unit] = withImages(root, false) { env =>
    stopTraffic(root) *> LabIo.run(root, Seq("docker", "compose", "down"), env).void
  }

  def trafficBuild(root: Path): IO[Unit] = withImages(root, true) { env =>
    LabIo.run(root, Seq("bash", "scripts/build-images.sh", "traffic-generator"), env).void
  }

  private def trafficArgs(options: Seq[String]): Seq[String] =
    Seq(
      "docker",
      "compose",
      "run",
      "--rm",
      "--no-deps",
      "-T",
      "traffic-generator",
      "--base-url",
      gateway,
    ) ++ options

  def trafficRun(root: Path, options: Seq[String]): IO[Unit] =
    imageEnvironment(root, true).flatMap(env => LabIo.run(root, trafficArgs(options), env))

  def trafficRunCaptured(root: Path, options: Seq[String]): IO[LabIo.ProcessResult] =
    imageEnvironment(root, true).flatMap(env => LabIo.capture(root, trafficArgs(options), env))

  def requireIdle(root: Path): IO[Unit] =
    LabIo
      .probe(root, Seq("docker", "inspect", "--format", "{{.State.Running}}", container))
      .flatMap { state =>
        IO.raiseWhen(state.exists(_.trim == "true"))(
          new IllegalStateException("Traffic is already running; stop it before verification"),
        )
      }

  def trafficResource(root: Path, options: Seq[String]): Resource[IO, Unit] =
    Resource.make(trafficStart(root, options))(_ => trafficStop(root))

  def containerId(root: Path, service: String): IO[String] =
    for
      value <- LabIo.output(root, Seq("docker", "compose", "ps", "--all", "-q", service))
      id     = value.trim
      _     <- IO.raiseUnless(id.nonEmpty && !id.contains('\n'))(
             new IllegalStateException(s"Expected one $service container"),
           )
    yield id

  def deployment(root: Path, service: String): IO[String] =
    for
      id    <- containerId(root, service)
      value <- LabIo.output(
                 root,
                 Seq("docker", "inspect", "--format", "container={{.Id}} image={{.Image}}", id),
               )
    yield value.trim

  def trafficStart(root: Path, options: Seq[String]): IO[Unit] = withImages(root, true) { env =>
    LabIo
      .probe(root, Seq("docker", "inspect", "--format", "{{.State.Running}}", container))
      .flatMap {
        case Some(value) if value.trim == "true" =>
          IO.raiseError(
            new IllegalStateException(
              "Traffic is already running. Use ./scripts/lab.sh traffic stop first.",
            ),
          )
        case Some(_) =>
          LabIo.output(root, Seq("docker", "container", "rm", container)).void
        case None => IO.unit
      } *> LabIo
      .logged(
        root,
        Seq(
          "docker",
          "compose",
          "run",
          "--detach",
          "--no-deps",
          "--name",
          container,
          "traffic-generator",
          "--duration",
          "infinite",
          "--base-url",
          gateway,
        ) ++ options,
        env,
      )
      .void
  }

  def trafficStatus(root: Path): IO[Unit] = for {
    state <- LabIo.output(
               root,
               Seq(
                 "docker",
                 "inspect",
                 "--format",
                 "state={{.State.Status}} exit_code={{.State.ExitCode}}",
                 container,
               ),
             )
    logs   <- LabIo.output(root, Seq("docker", "logs", "--tail", "20", container))
    entries = logs.linesIterator.filter(_.startsWith("{")).toVector
    _      <- IO.println(state.trim)
    _ <- IO.println("load_valid is cumulative; window.load_valid describes each report interval.")
    _ <- entries.find(_.contains("\"event\":\"preparation\"")).traverse_(IO.println)
    _ <-
      entries.filterNot(_.contains("\"event\":\"preparation\"")).takeRight(5).traverse_(IO.println)
  } yield ()

  def trafficStop(root: Path): IO[Unit] =
    LabIo.logged(root, Seq("docker", "stop", "--time", "40", container)).void

  def stopTraffic(root: Path): IO[Unit] =
    LabIo
      .probe(root, Seq("docker", "inspect", "--format", "{{.State.Running}}", container))
      .flatMap {
        case Some(value) if value.trim == "true" => trafficStop(root)
        case _ => IO.unit
      }

  def proxy(
      root: Path,
      action: ProxyAction,
      quiet: Boolean = false,
  ): IO[Unit] =
    withImages(root, false) { env =>
      val args = Seq(
        "docker",
        "compose",
        "run",
        "--rm",
        "--no-deps",
        "-T",
        "proxy-control",
      ) ++ action.arguments
      if quiet then LabIo.output(root, args, env).void else LabIo.run(root, args, env)
    }
}
