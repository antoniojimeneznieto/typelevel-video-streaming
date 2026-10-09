package org.typelevel.video.streaming.backend.runtime.auth

import java.util.UUID
import scala.util.Try

private[auth] object SubjectId:
  def canonical(value: String): Option[UUID] =
    Try(UUID.fromString(value)).toOption.filter(_.toString.equalsIgnoreCase(value))

  def compatible(value: String): Option[UUID] =
    canonical(value).orElse(
      Option.when(value.startsWith("user:"))(value.stripPrefix("user:")).flatMap(canonical),
    )
