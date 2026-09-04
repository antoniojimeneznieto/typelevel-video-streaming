package org.typelevel.video.streaming.backend.common.auth

import cats.effect.{IO, IOLocal}
import cats.syntax.all.*

import scala.util.control.NoStackTrace

final class CallerContext private (local: IOLocal[Option[Caller]]) {

  def current: IO[Option[Caller]] = local.get

  def require: IO[Caller] =
    local.get.flatMap(_.liftTo[IO](CallerContext.NoCallerInScope))

  private[auth] def set(caller: Caller): IO[Unit] = local.set(Some(caller))

}

object CallerContext {

  case object NoCallerInScope
      extends RuntimeException("No authenticated caller in scope")
      with NoStackTrace

  def make: IO[CallerContext] =
    IOLocal(Option.empty[Caller]).map(new CallerContext(_))

}
