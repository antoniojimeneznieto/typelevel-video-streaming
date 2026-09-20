package org.typelevel.video.streaming.backend.runtime.auth

import java.security.interfaces.RSAPublicKey
import java.util.UUID
import scala.util.Try

import cats.effect.IO
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.JWT

final class AccessTokenVerifier[Principal] private (
    publicKey: RSAPublicKey,
    issuer: String,
    audience: String,
    readPrincipal: DecodedJWT => Option[Principal],
) extends BearerTokenVerifier[IO, Principal]:

  private val verifier = JWT
    .require(Algorithm.RSA256(publicKey))
    .withIssuer(issuer)
    .withAudience(audience)
    .withClaimPresence("sub")
    .withClaimPresence("iat")
    .withClaimPresence("exp")
    .withClaimPresence("jti")
    .build()

  override def verify(token: String): IO[Option[Principal]] =
    IO.delay {
      val jwt = verifier.verify(token)

      for
        subject   <- Option(jwt.getSubject)
        _         <- parseUuid(subject)
        issuedAt  <- Option(jwt.getIssuedAtAsInstant)
        expiresAt <- Option(jwt.getExpiresAtAsInstant)
        if issuedAt.getEpochSecond >= 0 && expiresAt.isAfter(issuedAt)
        jwtId     <- Option(jwt.getId)
        _         <- parseUuid(jwtId)
        principal <- readPrincipal(jwt)
      yield principal
    }.recover { case _: JWTVerificationException => None }

  private def parseUuid(value: String): Option[UUID] =
    Try(UUID.fromString(value)).toOption
      .filter(_.toString.equalsIgnoreCase(value))

object AccessTokenVerifier:

  def apply[Principal](
      publicKey: RSAPublicKey,
      issuer: String,
      audience: String,
  )(readPrincipal: DecodedJWT => Option[Principal]): AccessTokenVerifier[Principal] =
    new AccessTokenVerifier(publicKey, issuer, audience, readPrincipal)

  def userId(
      publicKey: RSAPublicKey,
      issuer: String,
      audience: String,
  ): AccessTokenVerifier[UUID] =
    apply(publicKey, issuer, audience)(jwt => Some(UUID.fromString(jwt.getSubject)))
