package org.typelevel.video.streaming.lab

import cats.effect.{IO, Resource}
import cats.effect.std.Env
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.{Method, Request, Uri}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.`Content-Type`
import org.http4s.MediaType
import scala.concurrent.duration.*

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private[lab] object LabScenarios {
  private val idTime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC)

  private def ledger[A](
      root: Path,
  )(use: (Vector[PlatformChange], Vector[PlatformChange] => IO[Unit]) => IO[A]): IO[A] = {
    val dir = root.resolve(".lab")
    LabIo.createDirectories(dir) *>
      LabIo
        .exclusive(dir.resolve("platform.lock"))
        .use { _ =>
          val state = dir.resolve("platform-changes.json")
          for {
            exists  <- LabIo.exists(state)
            changes <-
              if exists then
                LabIo.read(state).flatMap { value =>
                  IO.fromEither(decode[Vector[PlatformChange]](value))
                }
              else IO.pure(Vector.empty[PlatformChange])
            save = (updated: Vector[PlatformChange]) =>
                     LabIo.writeAtomic(state, updated.asJson.spaces2 + "\n")
            result <- use(changes, save)
          } yield result
        }
  }

  /** Pending changes are recoverable if the process dies between the proxy and ledger writes. */
  def resetPlatform(root: Path): IO[Unit] = ledger(root) { (changes, save) =>
    for
      _   <- LabCommands.proxy(root, ProxyAction.Reset, quiet = true)
      now <- IO.realTimeInstant
      _   <- save(
             changes.map(change =>
               if change.status == ChangeStatus.RolledBack then change
               else change.rolledBack(now.toString),
             ),
           )
    yield ()
  }

  def requireNoActiveChange(root: Path): IO[Unit] = ledger(root) { (changes, _) =>
    IO.raiseWhen(changes.exists(_.status != ChangeStatus.RolledBack))(
      new IllegalStateException(
        "A traffic policy is active or pending; roll it back before verification",
      ),
    )
  }

  def platform(root: Path, action: PlatformAction): IO[Unit] = ledger(root) { (changes, save) =>
    def find(id: String): IO[PlatformChange] =
      IO.fromOption(changes.find(_.id == id))(new IllegalArgumentException("unknown change ID"))
    action match
      case PlatformAction.Changes =>
        changes.reverse.traverse_(change => IO.println(change.description))
      case PlatformAction.Inspect(id) => find(id).flatMap(change => IO.println(change.json.spaces2))
      case PlatformAction.Rollback(id) =>
        for
          change <- find(id)
          _      <-
            if change.status == ChangeStatus.RolledBack then IO.println(s"Already rolled back $id")
            else
              for
                _   <- LabCommands.proxy(root, ProxyAction.Reset, quiet = true)
                now <- IO.realTimeInstant
                _   <- save(
                       changes.map(current =>
                         if current.id == id then current.rolledBack(now.toString) else current,
                       ),
                     )
                _ <- IO.println(s"Rolled back $id")
              yield ()
        yield ()
  }

  private def nextChange(milliseconds: Int): IO[PlatformChange] =
    for
      now    <- IO.realTimeInstant
      suffix <- IO.randomUUID
    yield PlatformChange(
      s"traffic-policy-${idTime.format(now)}-${suffix.toString.take(6)}",
      ChangeStatus.Pending,
      JsonObject(
        "title" -> Json.fromString("East-west traffic policy rollout"),
        "applied_at" -> Json.fromString(now.toString),
        "configuration" -> Json.obj("catalog_egress_delay_ms" -> Json.fromInt(milliseconds)),
        "previous_configuration" -> Json.obj("catalog_egress_delay_ms" -> Json.fromInt(0)),
      ),
    )

  private def applyChange(
      root: Path,
      change: PlatformChange,
      milliseconds: Int,
      verbose: Boolean,
  ): IO[Unit] =
    ledger(root) { (changes, save) =>
      IO.uncancelable { poll =>
        def compensate: IO[Unit] = for
          _   <- LabCommands.proxy(root, ProxyAction.Reset, quiet = true)
          now <- IO.realTimeInstant
          _   <- save(changes :+ change.rolledBack(now.toString))
        yield ()
        for
          _ <- IO.raiseWhen(changes.exists(_.status != ChangeStatus.RolledBack))(
                 new IllegalArgumentException(
                   "traffic policy is active or pending; roll it back first",
                 ),
               )
          _ <- save(changes :+ change)
          _ <-
            (poll(LabCommands.proxy(root, ProxyAction.Latency(milliseconds), quiet = !verbose)) *>
              save(changes :+ change.copy(status = ChangeStatus.Active)))
              .onCancel(compensate)
              .onError { case _ => compensate }
        yield ()
      }
    }

  private[lab] def activatePlatformChange(
      root: Path,
      milliseconds: Int,
      verbose: Boolean = false,
  ): IO[String] =
    for
      change <- nextChange(milliseconds)
      _      <- applyChange(root, change, milliseconds, verbose)
    yield change.id

  private def rollbackIfPresent(root: Path, id: String): IO[Unit] = ledger(root) {
    (changes, save) =>
      changes
        .find(change => change.id == id && change.status != ChangeStatus.RolledBack)
        .traverse_ { change =>
          for
            _   <- LabCommands.proxy(root, ProxyAction.Reset, quiet = true)
            now <- IO.realTimeInstant
            _   <- save(
                   changes.map(current =>
                     if current.id == id then change.rolledBack(now.toString) else current,
                   ),
                 )
          yield ()
        }
  }

  def faultResource(root: Path, milliseconds: Int): Resource[IO, String] =
    Resource.eval(nextChange(milliseconds)).flatMap { change =>
      Resource.makeFull[IO, String] { poll =>
        poll(applyChange(root, change, milliseconds, false))
          .onCancel(rollbackIfPresent(root, change.id))
          .as(change.id)
      }(id => rollbackIfPresent(root, id))
    }

  def activatePlatform(root: Path, milliseconds: Int, verbose: Boolean = false): IO[Unit] =
    activatePlatformChange(root, milliseconds, verbose).flatMap { id =>
      IO.println(if verbose then s"Applied $id" else "Applied traffic policy")
    }

  /** Reconcile an interrupted lifecycle activation without adding a second policy. */
  def ensurePlatform(root: Path, milliseconds: Int, verbose: Boolean): IO[Unit] =
    for
      changes <- ledger(root)((values, _) => IO.pure(values))
      active   = changes.filter(_.status == ChangeStatus.Active)
      _       <- if active.nonEmpty then {
             IO.raiseUnless(
               active.size == 1 && active.head.json.hcursor
                 .downField("configuration")
                 .get[Int]("catalog_egress_delay_ms")
                 .contains(milliseconds),
             )(
               new IllegalStateException(
                 "An unrelated traffic policy is active; inspect platform changes before continuing.",
               ),
             )
           } else {
             (if changes.exists(_.status == ChangeStatus.Pending) then resetPlatform(root)
              else IO.unit) *>
               activatePlatform(root, milliseconds, verbose)
           }
    yield ()

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

  def scenario3(root: Path, action: Scenario3Action): IO[Unit] = action match {
    case Scenario3Action.Baseline =>
      LabCommands.trafficStart(
        root,
        Seq("--profile", "identity", "--rate", "30", "--login-percent", "5"),
      )
    case Scenario3Action.Activate =>
      LabCommands.stopTraffic(root) *>
        LabCommands.trafficStart(
          root,
          Seq("--profile", "identity", "--rate", "30", "--login-percent", "70"),
        ) *> IO.println("Workload rollout applied")
    case Scenario3Action.Restore =>
      LabCommands.stopTraffic(root) *> IO.println("Scenario 3 traffic stopped")
  }

  private def playbackMode(root: Path, enabled: Boolean): IO[Unit] =
    LabCommands.configure(
      root,
      "playback-service",
      Map("PLAYBACK_WORKSHOP_READ_MODE" -> enabled.toString),
    )

  private[lab] def preparePlayback(root: Path, configure: Boolean = true): IO[Unit] = {
    EmberClientBuilder.default[IO].withTimeout(15.seconds).build.use { client =>
      for
        gatewayPort <- Env[IO].get("GATEWAY_PORT").map(_.getOrElse("8085"))
        gateway     <-
          IO.fromEither(Uri.fromString(s"http://localhost:$gatewayPort/api/identity/users"))
        _ <- if configure then playbackMode(root, true) else IO.unit
        _ <- (0 until 10).toList.traverse_ { actor =>
               val kind  = if actor < 8 then "old" else "new"
               val email = s"lab-playback-$kind-$actor@example.invalid"
               val body  = Json
                 .obj(
                   "email" -> Json.fromString(email),
                   "password" -> Json.fromString("lab-playback-password-2026"),
                   "displayName" -> Json.fromString("Playback Lab Actor"),
                 )
                 .noSpaces
               val request = Request[IO](Method.POST, gateway)
                 .withEntity(body)
                 .putHeaders(`Content-Type`(MediaType.application.json))
               for {
                 status <- client
                             .run(request)
                             .use(response => response.body.compile.drain.as(response.status.code))
                 _ <- IO.raiseUnless(Set(201, 409)(status))(
                        new IllegalStateException(s"Registration for actor $actor returned $status"),
                      )
                 idText <- LabIo.output(
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
             }
        _ <- IO.println("Scenario data ready")
      yield ()
    }
  }

  def scenario4(root: Path, action: Scenario4Action): IO[Unit] = action match {
    case Scenario4Action.Prepare => preparePlayback(root)
    case Scenario4Action.Baseline | Scenario4Action.Activate =>
      LabCommands.stopTraffic(root) *>
        LabCommands.trafficStart(
          root,
          Seq(
            "--profile",
            "playback",
            "--rate",
            "10",
            "--modern-percent",
            if action == Scenario4Action.Baseline then "0" else "20",
          ),
        ) *>
        (if action == Scenario4Action.Activate then IO.println("Workload rollout applied")
         else IO.unit)
    case Scenario4Action.Restore =>
      LabCommands.stopTraffic(root) *> playbackMode(root, false) *>
        IO.println("Scenario 4 traffic stopped")
  }

  def scenario5(root: Path, action: Scenario5Action): IO[Unit] = action match {
    case Scenario5Action.Prepare =>
      LabCommands.configure(root, "catalog-service", Map("CATALOG_POSTGRES_MAX_CONNECTIONS" -> "6"))
    case Scenario5Action.Rebuild =>
      LabCommands.rebuild(root, "catalog-service", Map("CATALOG_POSTGRES_MAX_CONNECTIONS" -> "6"))
    case Scenario5Action.Baseline =>
      LabCommands.trafficStart(
        root,
        Seq("--profile", "catalog-courses", "--rate", "5", "--request-timeout", "15s"),
      )
    case Scenario5Action.Activate =>
      LabCommands.stopTraffic(root) *>
        LabCommands.trafficStart(
          root,
          Seq("--profile", "catalog-soak", "--rate", "5", "--request-timeout", "15s"),
        ) *>
        IO.println("Search workload rollout applied")
    case Scenario5Action.Restore =>
      LabCommands.stopTraffic(root) *>
        LabCommands.configure(
          root,
          "catalog-service",
          Map("CATALOG_POSTGRES_MAX_CONNECTIONS" -> "10"),
        ) *>
        IO.println("Scenario 5 traffic stopped; Catalog pool restored")
  }
}
