package org.typelevel.video.streaming.backend.user.service

import cats.effect.{IO, Resource}
import org.typelevel.video.streaming.backend.common.auth.CallerContext
import org.typelevel.video.streaming.backend.common.util.AuthedSession
import org.typelevel.video.streaming.backend.user.repository.UserProfileRepository
import org.typelevel.video.streaming.backend.user.{AccountService, Profile}
import skunk.Session

final class AccountServiceImpl(
    pool: Resource[IO, Session[IO]],
    repository: UserProfileRepository,
    callerContext: CallerContext,
) extends AuthedSession(pool, callerContext)
    with AccountService[IO] {

  def getProfile(): IO[Profile] =
    authed { (caller, session) =>
      repository.findByKeycloakId(session, caller.subject).flatMap {
        case Some(profile) => IO.pure(profile)
        case None          =>
          repository.insert(
            session,
            caller.subject,
            caller.preferredUsername,
            caller.email,
          )
      }
    }

}
