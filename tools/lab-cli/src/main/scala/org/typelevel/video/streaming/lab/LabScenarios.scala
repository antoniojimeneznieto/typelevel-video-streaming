package org.typelevel.video.streaming.lab

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.io.file.{Files, Flag, Flags, Path as Fs2Path}
import io.circe.{Json, JsonObject}
import io.circe.parser.parse

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.time.{Duration, Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.UUID

private[lab] object LabScenarios {
  private val idTime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC)

  private def ledger(
      root: Path,
  )(use: (Vector[Json], Vector[Json] => IO[Unit]) => IO[Unit]): IO[Unit] = {
    val dir = root.resolve(".lab")
    LabIo.createDirectories(dir) *>
      Files[IO]
        .open(Fs2Path.fromNioPath(dir.resolve("platform.lock")), Flags(Flag.Create, Flag.Write))
        .flatMap { handle =>
          Resource.make(handle.lock)(handle.unlock)
        }
        .use { _ =>
          val state = dir.resolve("platform-changes.json")
          for {
            exists  <- LabIo.exists(state)
            changes <-
              if exists then
                LabIo.read(state).flatMap { value =>
                  IO.fromEither(parse(value).flatMap(_.as[Vector[Json]]))
                }
              else IO.pure(Vector.empty[Json])
            save = (updated: Vector[Json]) =>
                     LabIo.writeAtomic(state, Json.fromValues(updated).spaces2 + "\n")
            _ <- use(changes, save)
          } yield ()
        }
  }

  private def field(change: Json, key: String): String =
    change.hcursor
      .get[String](key)
      .getOrElse(throw new IllegalStateException(s"Missing $key in change ledger"))

  def platform(root: Path, action: String, id: Option[String]): IO[Unit] = ledger(root) {
    (changes, save) =>
      if action == "changes" then
        changes.reverse.traverse_ { change =>
          IO.println(
            s"${field(change, "id")}  ${field(change, "applied_at")}  " +
              s"${field(change, "status")}  ${field(change, "title")}",
          )
        }
      else {
        val index = changes.indexWhere(change => field(change, "id") == id.get)
        IO.raiseWhen(index < 0)(new IllegalArgumentException("unknown change ID")) *>
          (if action == "inspect" then IO.println(changes(index).spaces2)
           else
             LabCommands.proxy(root, "reset", None) *>
               save(
                 changes.updated(
                   index,
                   changes(index).mapObject(
                     _.add("status", Json.fromString("rolled_back"))
                       .add("rolled_back_at", Json.fromString(Instant.now().toString)),
                   ),
                 ),
               ) *>
               IO.println(s"Rolled back ${id.get}"))
      }
  }

  def activatePlatform(root: Path, milliseconds: Int): IO[Unit] = ledger(root) { (changes, save) =>
    IO.raiseWhen(changes.exists(change => field(change, "status") == "active"))(
      new IllegalArgumentException("traffic policy is already active; roll it back first"),
    ) *>
      LabCommands.proxy(root, "latency", Some(milliseconds)) *>
      IO.defer {
        val id = s"traffic-policy-${idTime.format(Instant.now())}-" +
          UUID.randomUUID().toString.take(6)
        val change = Json.fromJsonObject(
          JsonObject(
            "id" -> Json.fromString(id),
            "title" -> Json.fromString("East-west traffic policy rollout"),
            "applied_at" -> Json.fromString(Instant.now().toString),
            "status" -> Json.fromString("active"),
            "configuration" -> Json.obj("catalog_egress_delay_ms" -> Json.fromInt(milliseconds)),
            "previous_configuration" -> Json.obj("catalog_egress_delay_ms" -> Json.fromInt(0)),
          ),
        )
        save(changes :+ change) *> IO.println(s"Applied $id")
      }
  }

  private[lab] def playbackEventId(id: UUID): UUID = {
    // Preserve the Python uuid5(NAMESPACE_URL, name) seed IDs.
    val namespace = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
    val bytes     = ByteBuffer
      .allocate(16)
      .putLong(namespace.getMostSignificantBits)
      .putLong(namespace.getLeastSignificantBits)
      .array()
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(bytes)
    val hash = digest.digest(s"playback-lab:$id".getBytes(StandardCharsets.UTF_8))
    hash(6) = ((hash(6) & 0x0f) | 0x50).toByte
    hash(8) = ((hash(8) & 0x3f) | 0x80).toByte
    val value = ByteBuffer.wrap(hash)
    new UUID(value.getLong(), value.getLong())
  }

  def scenario3(root: Path, action: String): IO[Unit] = action match {
    case "baseline" =>
      LabCommands.trafficStart(
        root,
        Seq("--profile", "identity", "--rate", "30", "--login-percent", "5"),
      )
    case "activate" =>
      LabCommands.stopTraffic(root) *>
        LabCommands.trafficStart(
          root,
          Seq("--profile", "identity", "--rate", "30", "--login-percent", "70"),
        ) *> IO.println("Workload rollout applied")
    case "restore" => LabCommands.stopTraffic(root) *> IO.println("Scenario 3 traffic stopped")
    case _ => IO.raiseError(new IllegalArgumentException("Unknown Scenario 3 action"))
  }

  private def playbackMode(root: Path, enabled: Boolean): IO[Unit] =
    LabCommands.rebuild(
      root,
      "playback-service",
      Map("PLAYBACK_WORKSHOP_READ_MODE" -> enabled.toString),
    )

  private def preparePlayback(root: Path): IO[Unit] = {
    val client      = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    val gatewayPort = sys.env.getOrElse("GATEWAY_PORT", "8085")
    playbackMode(root, true) *> (0 until 10).toList.traverse_ { actor =>
      val kind  = if actor < 8 then "old" else "new"
      val email = s"lab-playback-$kind-$actor@example.invalid"
      val body  = Json
        .obj(
          "email" -> Json.fromString(email),
          "password" -> Json.fromString("lab-playback-password-2026"),
          "displayName" -> Json.fromString("Playback Lab Actor"),
        )
        .noSpaces
      val request = HttpRequest
        .newBuilder(URI.create(s"http://localhost:$gatewayPort/api/identity/users"))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(15))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      for {
        response <- IO.blocking(client.send(request, HttpResponse.BodyHandlers.discarding()))
        _        <- IO.raiseUnless(Set(201, 409)(response.statusCode()))(
               new IllegalStateException(
                 s"Registration for actor $actor returned ${response.statusCode()}",
               ),
             )
        idText <- LabIo.run(
                    root,
                    Seq(
                      "docker",
                      "compose",
                      "exec",
                      "-T",
                      "postgres",
                      "psql",
                      "-U",
                      "postgres",
                      "-d",
                      "identity",
                      "-At",
                      "-c",
                      s"SELECT id FROM users WHERE email = '$email'",
                    ),
                    capture = true,
                  )
        id     <- IO(UUID.fromString(idText.trim))
        eventId = playbackEventId(id)
        _      <- LabIo
               .run(
                 root,
                 Seq(
                   "docker",
                   "compose",
                   "exec",
                   "-T",
                   "postgres",
                   "psql",
                   "-U",
                   "postgres",
                   "-d",
                   "playback",
                   "-q",
                   "-c",
                   "INSERT INTO users (id, event_id, created_at) " +
                     s"VALUES ('$id', '$eventId', now()) ON CONFLICT (id) DO NOTHING",
                 ),
               )
               .void
      } yield ()
    } *> IO.println("Ten actors registered; Playback user projections seeded")
  }

  def scenario4(root: Path, action: String): IO[Unit] = action match {
    case "prepare" => preparePlayback(root)
    case "baseline" | "activate" =>
      LabCommands.stopTraffic(root) *>
        LabCommands.trafficStart(
          root,
          Seq(
            "--profile",
            "playback",
            "--rate",
            "10",
            "--modern-percent",
            if action == "baseline" then "0" else "20",
          ),
        ) *>
        (if action == "activate" then IO.println("Workload rollout applied") else IO.unit)
    case "restore" =>
      LabCommands.stopTraffic(root) *> playbackMode(root, false) *>
        IO.println("Scenario 4 traffic stopped")
    case _ => IO.raiseError(new IllegalArgumentException("Unknown Scenario 4 action"))
  }
}
