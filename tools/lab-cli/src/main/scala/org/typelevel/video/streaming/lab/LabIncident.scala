package org.typelevel.video.streaming.lab

import cats.effect.IO
import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*
import java.nio.file.Path

/** Durable phases make retries explicit. Runtime operations are injectable for failure tests. */
private[lab] object LabIncident:
  enum Phase:
    case Preparing, Observing, Ready, Activating, Active, Rebuilding, Deployed

  given Codec[Phase] = Codec.from(
    io.circe
      .Decoder[String]
      .emap(value => Phase.values.find(_.toString == value).toRight("Unknown incident phase")),
    io.circe.Encoder[String].contramap(_.toString),
  )

  final case class State(
      code: String,
      phase: Phase,
      since: Long,
      deployment: Map[String, String],
      milliseconds: Int = 750,
  )
  given Codec[State] =
    Codec.forProduct5("code", "phase", "since", "deployment", "milliseconds")(State.apply)(s =>
      (s.code, s.phase, s.since, s.deployment, s.milliseconds),
    )

  trait Operations:
    def preflight: IO[Unit]
    def prepare(code: String, build: Boolean, restart: Boolean): IO[Unit]
    def baseline(code: String): IO[Unit]
    def snapshot: IO[Map[String, String]]
    def observe(code: String, since: Long, healthy: Boolean): IO[Unit]
    def activate(code: String, milliseconds: Int, verbose: Boolean): IO[Unit]
    def traffic(code: String): IO[Unit]
    def rebuild(code: String): IO[Unit]
    def source(code: String): IO[String]
    def ready(code: String): IO[Unit]

  def load(root: Path): IO[Option[State]] =
    val file        = root.resolve(".lab/incident.json")
    val previousIds = Map("8f27" -> "1", "3c91" -> "3", "7b42" -> "4", "d5e0" -> "5")
    for
      exists <- LabIo.exists(file)
      state  <-
        if exists then
          LabIo
            .read(file)
            .flatMap(value => IO.fromEither(decode[State](value)))
            .map(state => Some(state.copy(code = previousIds.getOrElse(state.code, state.code))))
        else IO.pure(None)
    yield state

  final class Lifecycle(root: Path, operations: Operations):
    private def save(state: State): IO[Unit] =
      for
        _   <- LabIo.writeAtomic(root.resolve(".lab/incident.json"), state.asJson.spaces2 + "\n")
        now <- IO.realTimeInstant
        _   <- LabIo.append(
               root.resolve(".lab/incident-history.jsonl"),
               state.asJson
                 .deepMerge(
                   io.circe.Json.obj("recorded_at" -> io.circe.Json.fromString(now.toString)),
                 )
                 .noSpaces + "\n",
             )
      yield ()

    private def unchanged(state: State): IO[Unit] =
      for
        actual <- operations.snapshot
        _      <-
          IO.raiseUnless(actual == state.deployment)(
            new IllegalStateException(
              s"The scenario deployment changed outside this command. Use incident rebuild to deploy edits, or incident restart ${state.code} for a fresh exercise. Source edits are preserved.",
            ),
          )
      yield ()

    private def observe(state: State, healthy: Boolean): IO[Unit] =
      for
        _ <- unchanged(state)
        _ <- operations.observe(state.code, state.since, healthy)
        _ <- save(state.copy(phase = if healthy then Phase.Ready else Phase.Active))
        _ <-
          if healthy then operations.ready(state.code)
          else
            IO.println(
              "Deployment ready. Keep investigating and verify the result under the same workload.",
            )
      yield ()

    def start(code: String, build: Boolean, restart: Boolean): IO[Unit] =
      for
        _        <- operations.preflight
        previous <- load(root)
        _        <- previous.filter(s => s.code == code && !restart) match
               case Some(state) if state.phase == Phase.Active =>
                 unchanged(state) *> operations.traffic(code) *>
                   IO.println(
                     "Incident already active. Existing traffic and evidence were preserved.",
                   )
               case Some(state) if state.phase == Phase.Activating =>
                 IO.raiseError(
                   new IllegalStateException(
                     s"Activation was interrupted. Retry incident activate $code.",
                   ),
                 )
               case Some(state)
                   if state.phase == Phase.Rebuilding || state.phase == Phase.Deployed =>
                 IO.raiseError(
                   new IllegalStateException("Deployment was interrupted. Retry incident rebuild."),
                 )
               case Some(state) if state.phase == Phase.Ready || state.phase == Phase.Observing =>
                 observe(state, healthy = true)
               case _ =>
                 for
                   now        <- IO.realTime.map(_.toMillis)
                   state       = State(code, Phase.Preparing, now, Map.empty)
                   _          <- save(state)
                   _          <- operations.prepare(code, build, restart)
                   _          <- operations.baseline(code)
                   since      <- IO.realTime.map(_.toMillis)
                   deployment <- operations.snapshot
                   observing   =
                     state.copy(phase = Phase.Observing, since = since, deployment = deployment)
                   _ <- save(observing)
                   _ <- observe(observing, healthy = true)
                 yield ()
      yield ()

    def activate(code: String, milliseconds: Int, verbose: Boolean): IO[Unit] =
      for
        state <- required
        _     <- IO.raiseUnless(state.code == code)(
               new IllegalStateException(s"Prepare this scenario first: incident start $code"),
             )
        _ <- unchanged(state)
        _ <- state.phase match
               case Phase.Active =>
                 operations.traffic(code) *> IO.println(
                   "Incident already active; no change reapplied.",
                 )
               case Phase.Ready | Phase.Activating =>
                 for
                   _ <-
                     if state.phase == Phase.Ready then
                       operations.observe(code, state.since, healthy = true)
                     else
                       IO.raiseUnless(state.milliseconds == milliseconds)(
                         new IllegalArgumentException("Retry with the original activation options."),
                       )
                   now       <- IO.realTime.map(_.toMillis)
                   activating =
                     state.copy(phase = Phase.Activating, since = now, milliseconds = milliseconds)
                   _ <- save(activating)
                   _ <- operations.activate(code, milliseconds, verbose)
                   _ <- save(activating.copy(phase = Phase.Active))
                   _ <- operations.traffic(code)
                   _ <-
                     IO.println(
                       s"Incident activated at ${java.time.Instant.ofEpochMilli(now)}. Investigate the fresh traffic.",
                     )
                 yield ()
               case _ =>
                 IO.raiseError(
                   new IllegalStateException(
                     s"Baseline is not ready. Complete incident start $code first.",
                   ),
                 )
      yield ()

    private def required: IO[State] = load(root).flatMap(value =>
      IO.fromOption(value)(
        new IllegalStateException("No scenario selected. Run incident start ID first."),
      ),
    )

    def rebuild: IO[Unit] =
      for
        state <- required
        _     <- IO.raiseWhen(state.code == "1")(
               new IllegalStateException(
                 "This incident uses platform rollback; no service rebuild is needed.",
               ),
             )
        _ <- IO.raiseUnless(Set(Phase.Active, Phase.Rebuilding, Phase.Deployed)(state.phase))(
               new IllegalStateException(
                 "Activate the scenario before deploying diagnostic or repair edits.",
               ),
             )
        source      <- operations.source(state.code)
        buildFile    = root.resolve(".lab/incident-build.sha256")
        buildExists <- LabIo.exists(buildFile)
        lastBuild   <- if buildExists then LabIo.read(buildFile).map(Some(_)) else IO.pure(None)
        _           <-
          if state.phase == Phase.Deployed && lastBuild.contains(source) then
            observe(state, healthy = false)
          else
            for
              _          <- save(state.copy(phase = Phase.Rebuilding))
              _          <- operations.rebuild(state.code)
              built      <- operations.source(state.code)
              _          <- LabIo.writeAtomic(buildFile, built)
              now        <- IO.realTime.map(_.toMillis)
              deployment <- operations.snapshot
              deployed    = state.copy(phase = Phase.Deployed, since = now, deployment = deployment)
              _          <- save(deployed)
              _          <- observe(deployed, healthy = false)
            yield ()
      yield ()

    def status: IO[Unit] =
      for
        state  <- required
        actual <- operations.snapshot.attempt
        _      <-
          IO.println(
            s"Scenario ${state.code}: ${state.phase}; since ${java.time.Instant.ofEpochMilli(state.since)}",
          )
        _ <- IO.println(if actual.contains(state.deployment) then
               "Recorded deployment matches running services."
             else "Deployment changed or services are unavailable.")
      yield ()
