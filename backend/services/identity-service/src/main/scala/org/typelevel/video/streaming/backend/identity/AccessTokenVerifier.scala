package org.typelevel.video.streaming.backend.identity.service

import java.security.interfaces.RSAPublicKey
import java.util.UUID

import cats.effect.IO
import com.auth0.jwt.interfaces.DecodedJWT
import org.typelevel.video.streaming.backend.identity.auth.*
import org.typelevel.video.streaming.backend.identity.domain.{Role, UserId}
import org.typelevel.video.streaming.backend.runtime.auth.{AccessTokenVerifier, BearerTokenVerifier}

final class AccessTokenVerifierImpl private (
    publicKey: RSAPublicKey
) extends BearerTokenVerifier[IO, AccessTokenClaims]:

  private val verifier = AccessTokenVerifier(
    publicKey,
    TokenIssuer.IDENTITY.stringValue,
    TokenAudience.COURSE_PLATFORM.stringValue
  )(readClaims)

  override def verify(token: String): IO[Option[AccessTokenClaims]] =
    verifier.verify(token)

  private def readClaims(jwt: DecodedJWT): Option[AccessTokenClaims] =
    for
      roleName  <- Option(jwt.getClaim("role").asString())
      role      <- Role.values.find(_.stringValue == roleName)
      issuedAt  <- JwtNumericDate(jwt.getIssuedAtAsInstant.getEpochSecond).toOption
      expiresAt <- JwtNumericDate(jwt.getExpiresAtAsInstant.getEpochSecond).toOption
    yield AccessTokenClaims(
      iss  = TokenIssuer.IDENTITY,
      sub  = UserId(UUID.fromString(jwt.getSubject)),
      aud  = TokenAudience.COURSE_PLATFORM,
      role = role,
      iat  = issuedAt,
      exp  = expiresAt,
      jti  = JwtId(UUID.fromString(jwt.getId))
    )

object AccessTokenVerifierImpl:

  def apply(publicKey: RSAPublicKey): AccessTokenVerifierImpl =
    new AccessTokenVerifierImpl(publicKey)
