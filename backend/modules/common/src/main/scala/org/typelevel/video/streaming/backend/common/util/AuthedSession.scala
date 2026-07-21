package org.typelevel.video.streaming.backend.common.util

import cats.effect.{IO, Resource}
import org.typelevel.video.streaming.backend.common.auth.{Caller, CallerContext}
import skunk.Session

abstract class AuthedSession(pool: Resource[IO, Session[IO]], callerContext: CallerContext):

  protected def authed[A](f: (Caller, Session[IO]) => IO[A]): IO[A] =
    callerContext.require.flatMap(caller => pool.use(f(caller, _)))

  protected def authedTx[A](f: (Caller, Session[IO]) => IO[A]): IO[A] =
    callerContext.require.flatMap { caller =>
      pool.use(session => session.transaction.use(_ => f(caller, session)))
    }
