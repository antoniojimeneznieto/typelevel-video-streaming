package org.typelevel.video.streaming.backend.runtime.auth

import java.util.UUID

import cats.effect.IO

/** Observes application subject decoding without changing its authentication result. `subject` is
  * the verified JWT subject; implementations must choose deliberately what to export.
  */
trait AccessTokenVerifierTelemetry:
  def decodeSubject(
      subject: Option[String],
      shape: AccessTokenVerifierTelemetry.SubjectShape,
      decode: IO[Option[UUID]],
  ): IO[Option[UUID]]

object AccessTokenVerifierTelemetry:
  enum SubjectShape(val label: String):
    case BareUuid extends SubjectShape("bare_uuid")
    case NamespacedUuid extends SubjectShape("namespaced_uuid")
    case Other extends SubjectShape("other")

  object SubjectShape:
    /** Fixed categories only; the category never contains the subject or namespace. */
    def from(subject: Option[String]): SubjectShape = subject match
      case Some(value) if canonicalUuid(value) => SubjectShape.BareUuid
      case Some(value) =>
        val separator = value.indexOf(':')
        if separator > 0 && separator == value.lastIndexOf(':') &&
          canonicalUuid(value.substring(separator + 1))
        then SubjectShape.NamespacedUuid
        else SubjectShape.Other
      case None => SubjectShape.Other

    private def canonicalUuid(value: String): Boolean =
      SubjectId.canonical(value).isDefined

  val noop: AccessTokenVerifierTelemetry = new AccessTokenVerifierTelemetry:
    override def decodeSubject(
        subject: Option[String],
        shape: SubjectShape,
        decode: IO[Option[UUID]],
    ): IO[Option[UUID]] =
      decode
