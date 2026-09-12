package org.typelevel.video.streaming.backend.runtime.auth

trait BearerTokenVerifier[F[_], Principal]:

  def verify(token: String): F[Option[Principal]]
