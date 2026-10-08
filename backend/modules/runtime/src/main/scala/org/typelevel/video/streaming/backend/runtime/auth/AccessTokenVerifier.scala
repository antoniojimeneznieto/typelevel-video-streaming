package org.typelevel.video.streaming.backend.runtime.auth

import java.security.interfaces.RSAPublicKey
import java.util.UUID

import cats.effect.IO
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.{DecodedJWT, JWTVerifier}
import com.auth0.jwt.JWT

final class AccessTokenVerifier[Principal] private (
    verifier: JWTVerifier,
    readPrincipal: (DecodedJWT, UUID) => Option[Principal],
    readSubject: String => Option[UUID],
    telemetry: AccessTokenVerifierTelemetry,
) extends BearerTokenVerifier[IO, Principal]:

  def withTelemetry(observe: AccessTokenVerifierTelemetry): AccessTokenVerifier[Principal] =
    new AccessTokenVerifier(verifier, readPrincipal, readSubject, observe)

  override def verify(token: String): IO[Option[Principal]] =
    (for
      jwt     <- IO.delay(verifier.verify(token))
      subject  = Option(jwt.getSubject)
      decoded <- telemetry.decodeSubject(
                   subject,
                   AccessTokenVerifierTelemetry.SubjectShape.from(subject),
                   IO.delay(subject.flatMap(readSubject)),
                 )
    yield
      for
        userId    <- decoded
        issuedAt  <- Option(jwt.getIssuedAtAsInstant)
        expiresAt <- Option(jwt.getExpiresAtAsInstant)
        if issuedAt.getEpochSecond >= 0 && expiresAt.isAfter(issuedAt)
        jwtId     <- Option(jwt.getId)
        _         <- SubjectId.canonical(jwtId)
        principal <- readPrincipal(jwt, userId)
      yield principal).recover { case _: JWTVerificationException => None }

object AccessTokenVerifier:

  def apply[Principal](
      publicKey: RSAPublicKey,
      issuer: String,
      audience: String,
  )(readPrincipal: DecodedJWT => Option[Principal]): AccessTokenVerifier[Principal] =
    new AccessTokenVerifier(
      validator(publicKey, issuer, audience),
      (jwt, _) => readPrincipal(jwt),
      SubjectId.canonical,
      AccessTokenVerifierTelemetry.noop,
    )

  def userId(
      publicKey: RSAPublicKey,
      issuer: String,
      audience: String,
  ): AccessTokenVerifier[UUID] =
    new AccessTokenVerifier(
      validator(publicKey, issuer, audience),
      (_, id) => Some(id),
      SubjectId.canonical,
      AccessTokenVerifierTelemetry.noop,
    )

  /** Used by Identity during the subject-format migration. Playback intentionally uses userId. */
  def userIdCompatible(
      publicKey: RSAPublicKey,
      issuer: String,
      audience: String,
  ): AccessTokenVerifier[UUID] =
    new AccessTokenVerifier(
      validator(publicKey, issuer, audience),
      (_, id) => Some(id),
      SubjectId.compatible,
      AccessTokenVerifierTelemetry.noop,
    )

  private def validator(publicKey: RSAPublicKey, issuer: String, audience: String): JWTVerifier =
    JWT
      .require(Algorithm.RSA256(publicKey))
      .withIssuer(issuer)
      .withAudience(audience)
      .withClaimPresence("sub")
      .withClaimPresence("iat")
      .withClaimPresence("exp")
      .withClaimPresence("jti")
      .build()
