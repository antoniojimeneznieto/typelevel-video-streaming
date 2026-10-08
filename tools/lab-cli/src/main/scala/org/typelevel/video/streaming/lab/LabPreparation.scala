package org.typelevel.video.streaming.lab

import scala.concurrent.duration.*

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse

import java.nio.file.Path

/** Controlled workloads isolate the checked-in defects; this is not a safe browsing mode. */
private[lab] object LabPreparation {
  private val services =
    List("identity-service", "catalog-service", "playback-service", "gateway-service")

  private[lab] def healthy(state: Json): Boolean =
    state.hcursor.get[Boolean]("Running").contains(true) &&
      state.hcursor.downField("Health").get[String]("Status").contains("healthy")

  private def containerId(root: Path, service: String): IO[String] =
    LabIo
      .output(root, Seq("docker", "compose", "ps", "--all", "-q", service))
      .map(_.trim)
      .flatMap { id =>
        IO.raiseUnless(id.nonEmpty && !id.contains('\n'))(
          new IllegalStateException(s"Expected one $service container; run lab.sh start first"),
        ).as(id)
      }

  private def awaitHealth(root: Path, id: String): IO[Unit] = {
    def check: IO[Unit] =
      LabIo
        .output(root, Seq("docker", "inspect", "--format", "{{json .State}}", id))
        .flatMap(value => IO.fromEither(parse(value)))
        .flatMap(state => if healthy(state) then IO.unit else IO.sleep(1.second) *> IO.defer(check))
    check.timeout(90.seconds)
  }

  def prepare(root: Path, round: Int): IO[Unit] = {
    val record = root.resolve(".lab/preparation.json")
    for {
      _ <- IO.raiseUnless(Set(1, 3, 4, 5)(round))(new IllegalArgumentException("Unsupported round"))
      _ <- LabIo.createDirectories(root.resolve(".lab"))
      _ <- LabIo.writeAtomic(
             record,
             Json
               .obj(
                 "round" -> Json.fromInt(round),
                 "status" -> Json.fromString("preparing"),
               )
               .spaces2 + "\n",
           )
      ids <- services.traverse(service => containerId(root, service))
      _   <- LabCommands.stopTraffic(root)
      // Restart existing containers, preserving their actual image and environment.
      // Recreating from bare Compose here would lose scenario-specific settings.
      _ <- LabIo.run(root, Seq("docker", "restart", "--time", "10") ++ ids).timeout(90.seconds)
      _ <- ids.traverse_(awaitHealth(root, _))
      _ <- LabScenarios.resetPlatform(root)
      _ <- round match {
             case 4 => LabScenarios.scenario4(root, Scenario4Action.Prepare)
             case 5 => LabScenarios.scenario5(root, Scenario5Action.Prepare)
             case _ => IO.unit
           }
      currentIds <- services.traverse(service => containerId(root, service))
      _          <- currentIds.traverse_(awaitHealth(root, _))
      _          <- LabCommands.proxy(root, ProxyAction.Check)
      // Record only safe deployment metadata; container environments include secrets.
      deployed <-
        services.zip(currentIds).traverse { case (service, id) =>
          LabIo
            .output(root, Seq("docker", "inspect", "--format", "{{.Image}}", id))
            .map(image =>
              service -> Json
                .obj("container" -> Json.fromString(id), "image" -> Json.fromString(image.trim)),
            )
        }
      preparedAt <- IO.realTimeInstant
      _          <- LabIo.writeAtomic(
             record,
             Json
               .obj(
                 "round" -> Json.fromInt(round),
                 "status" -> Json.fromString("prepared"),
                 "prepared_at" -> Json.fromString(preparedAt.toString),
                 "services" -> Json.obj(deployed*),
               )
               .spaces2 + "\n",
           )
      _ <-
        IO.println(
          s"Round $round prepared. Start its prescribed baseline and check fresh telemetry before activation.",
        )
      _ <-
        IO.println(
          "Use only the prescribed workload; arbitrary browser searches can trigger latent faults. Source repairs are not reverted.",
        )
    } yield ()
  }
}
