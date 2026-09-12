package org.typelevel.video.streaming.backend.identity.service

import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.UUID

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.JWT
import org.typelevel.video.streaming.backend.identity.api.{AccessToken, ExpiresInSeconds}
import org.typelevel.video.streaming.backend.identity.auth.{TokenAudience, TokenIssuer}
import org.typelevel.video.streaming.backend.identity.domain.{Role, UserId}

trait AccessTokenIssuer:

  def issue(userId: UserId, role: Role): IO[IssuedAccessToken]

final case class IssuedAccessToken(
    accessToken: AccessToken,
    expiresIn: ExpiresInSeconds
)

final class AccessTokenIssuerImpl private[service] (
    privateKey: RSAPrivateKey,
    expiresIn: ExpiresInSeconds,
    currentInstant: IO[Instant],
    newJwtId: IO[UUID]
) extends AccessTokenIssuer:

  private val algorithm = Algorithm.RSA256(privateKey)

  override def issue(userId: UserId, role: Role): IO[IssuedAccessToken] =
    for
      issuedAt <- currentInstant
      jwtId    <- newJwtId
      token    <- IO.blocking {
                 JWT
                   .create()
                   .withIssuer(TokenIssuer.IDENTITY.stringValue)
                   .withSubject(UserId.value(userId).toString)
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
      expiresIn: ExpiresInSeconds
  ): AccessTokenIssuerImpl =
    new AccessTokenIssuerImpl(
      privateKey     = privateKey,
      expiresIn      = expiresIn,
      currentInstant = Clock[IO].realTimeInstant,
      newJwtId       = IO(UUID.randomUUID())
    )
