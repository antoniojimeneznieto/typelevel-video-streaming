package org.typelevel.video.streaming.backend.identity.service

import java.security.interfaces.RSAPrivateKey
import java.util.UUID

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.JWT
import org.typelevel.video.streaming.backend.identity.api.{AccessToken, ExpiresInSeconds}
import org.typelevel.video.streaming.backend.identity.auth.{TokenAudience, TokenIssuer}
import org.typelevel.video.streaming.backend.identity.domain.{Email, Role, UserId}

trait AccessTokenIssuer:

  def issue(userId: UserId, role: Role, email: Email): IO[IssuedAccessToken]

final case class IssuedAccessToken(
    accessToken: AccessToken,
    expiresIn: ExpiresInSeconds,
)

final class AccessTokenIssuerImpl private (
    privateKey: RSAPrivateKey,
    expiresIn: ExpiresInSeconds,
    workshopSubjectMigration: Boolean,
) extends AccessTokenIssuer:

  private val algorithm = Algorithm.RSA256(privateKey)

  override def issue(userId: UserId, role: Role, email: Email): IO[IssuedAccessToken] =
    for
      issuedAt <- Clock[IO].realTimeInstant
      jwtId    <- IO(UUID.randomUUID())
      token    <- IO.delay {
                 JWT
                   .create()
                   .withIssuer(TokenIssuer.IDENTITY.stringValue)
                   .withSubject(
                     (if workshopSubjectMigration &&
                        Email.value(email).matches("lab-playback-new-[0-9]+@example\\.invalid")
                      then "user:"
                      else "") + UserId.value(userId).toString,
                   )
                   .withAudience(TokenAudience.COURSE_PLATFORM.stringValue)
                   .withClaim("role", role.stringValue)
                   .withIssuedAt(issuedAt)
                   .withExpiresAt(issuedAt.plusSeconds(ExpiresInSeconds.value(expiresIn).toLong))
                   .withJWTId(jwtId.toString)
                   .sign(algorithm)
               }
      accessToken <- AccessToken(token)
                       .leftMap(new IllegalStateException(_))
                       .liftTo[IO]
    yield IssuedAccessToken(accessToken, expiresIn)

object AccessTokenIssuerImpl:

  def apply(
      privateKey: RSAPrivateKey,
      expiresIn: ExpiresInSeconds,
      workshopSubjectMigration: Boolean = false,
  ): AccessTokenIssuerImpl =
    new AccessTokenIssuerImpl(privateKey, expiresIn, workshopSubjectMigration)
