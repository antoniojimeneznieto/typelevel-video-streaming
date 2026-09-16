package org.typelevel.video.streaming.backend.runtime.context

import cats.effect.{IO, IOLocal}

final class IOLocalRequestContext[A] private (
    local: IOLocal[Option[A]],
) extends RequestContext[IO, A]:

  override def get: IO[Option[A]] =
    local.get

  override def scope[B](value: A)(effect: IO[B]): IO[B] =
    local.get.flatMap { previous =>
      local.set(Some(value)) *>
        effect.guarantee(local.set(previous))
    }

object IOLocalRequestContext:

  def create[A]: IO[IOLocalRequestContext[A]] =
    IOLocal(Option.empty[A]).map(new IOLocalRequestContext(_))
