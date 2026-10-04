package org.typelevel.video.streaming.lab

import cats.effect.IO
import cats.syntax.all.*

import java.nio.file.Path

private[lab] object LabCommands {
  private val container = "typelevel-video-streaming-lab-traffic"
  private val gateway   = "http://gateway-service:8084"

  private def imageEnvironment(root: Path, followRunning: Boolean): IO[Map[String, String]] = {
    val detected =
      if followRunning && !sys.env.contains("IMAGE_PREFIX") &&
        !sys.env.contains("IMAGE_TAG")
      then
        LabIo
          .probe(root, Seq("docker", "compose", "ps", "-q", "gateway-service"))
          .flatMap {
            case Some(id) if id.trim.nonEmpty =>
              LabIo.probe(root, Seq("docker", "inspect", "--format", "{{.Config.Image}}", id.trim))
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

    detected.flatMap { environment =>
      val script = """set -euo pipefail
project_directory="$PWD"
source scripts/images.sh
printf 'IMAGE_PREFIX=%s\nIMAGE_TAG=%s\nLOCAL_UID=%s\nLOCAL_GID=%s\nS3_PUBLIC_ENDPOINT=%s\n' \
  "$IMAGE_PREFIX" "$IMAGE_TAG" "$LOCAL_UID" "$LOCAL_GID" "${S3_PUBLIC_ENDPOINT:-}"
"""
      LabIo.run(root, Seq("bash", "-c", script), environment, capture = true).map { output =>
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

  def rebuild(root: Path, service: String, extra: Map[String, String] = Map.empty): IO[Unit] =
    withImages(root, true) { env =>
      val environment = env ++ extra
      LabIo.run(root, Seq("bash", "scripts/build-images.sh", service), environment).void *>
        LabIo
          .run(
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
          .void
    }

  def stopStack(root: Path): IO[Unit] = withImages(root, false) { env =>
    stopTraffic(root) *> LabIo.run(root, Seq("docker", "compose", "down"), env).void
  }

  def trafficBuild(root: Path): IO[Unit] = withImages(root, true) { env =>
    LabIo.run(root, Seq("bash", "scripts/build-images.sh", "traffic-generator"), env).void
  }

  def trafficRun(root: Path, options: Seq[String]): IO[Unit] = withImages(root, true) { env =>
    LabIo
      .run(
        root,
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
        ) ++ options,
        env,
      )
      .void
  }

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
          LabIo.run(root, Seq("docker", "container", "rm", container), capture = true).void
        case None => IO.unit
      } *> LabIo
      .run(
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
    state <- LabIo.run(
               root,
               Seq(
                 "docker",
                 "inspect",
                 "--format",
                 "state={{.State.Status}} exit_code={{.State.ExitCode}}",
                 container,
               ),
               capture = true,
             )
    logs <- LabIo.run(root, Seq("docker", "logs", "--tail", "200", container), capture = true)
    _    <- IO.println(state.trim)
    _ <- IO.println("load_valid is cumulative; window.load_valid describes each report interval.")
    _ <- logs.linesIterator.filter(_.startsWith("{")).toVector.takeRight(5).traverse_(IO.println)
  } yield ()

  def trafficStop(root: Path): IO[Unit] =
    LabIo.run(root, Seq("docker", "stop", "--time", "40", container)).void

  def stopTraffic(root: Path): IO[Unit] =
    LabIo
      .probe(root, Seq("docker", "inspect", "--format", "{{.State.Running}}", container))
      .flatMap {
        case Some(value) if value.trim == "true" => trafficStop(root)
        case _ => IO.unit
      }

  def proxy(root: Path, action: String, milliseconds: Option[Int]): IO[Unit] =
    withImages(root, false) { env =>
      LabIo
        .run(
          root,
          Seq(
            "docker",
            "compose",
            "run",
            "--rm",
            "--no-deps",
            "-T",
            "proxy-control",
            action,
          ) ++ milliseconds.map(_.toString),
          env,
        )
        .void
    }
}
