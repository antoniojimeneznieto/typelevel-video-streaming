package org.typelevel.video.streaming.backend.playback

import java.util.UUID

import cats.effect.IO
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import org.typelevel.video.streaming.backend.runtime.auth.AccessTokenVerifierTelemetry
import org.typelevel.video.streaming.backend.runtime.auth.AccessTokenVerifierTelemetry.SubjectShape

/** Workshop observation point; validation behavior stays in the supplied effect. */
final class PlaybackAuthTelemetry(using Tracer[IO], LoggerFactory[IO])
    extends AccessTokenVerifierTelemetry:
  val logger = LoggerFactory[IO].getLoggerFromClass(getClass)

  override def decodeSubject(
      subject: Option[String],
      shape: SubjectShape,
      decode: IO[Option[UUID]],
  ): IO[Option[UUID]] = Tracer[IO].span("auth.subject.decode").surround(decode)

object PlaybackAuthTelemetry:
  def create(using TracerProvider[IO], LoggerFactory[IO]): IO[PlaybackAuthTelemetry] =
    TracerProvider[IO]
      .get("org.typelevel.video.streaming.playback.auth")
      .map { case given Tracer[IO] => new PlaybackAuthTelemetry }
