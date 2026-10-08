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
    readSubject: String => Option[UUID],
    telemetry: AccessTokenVerifierTelemetry,
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

  def withTelemetry(observe: AccessTokenVerifierTelemetry): AccessTokenVerifier[Principal] =
    new AccessTokenVerifier(publicKey, issuer, audience, readPrincipal, readSubject, observe)

  override def verify(token: String): IO[Option[Principal]] =
    IO.delay(verifier.verify(token)).flatMap { jwt =>
      val subject = Option(jwt.getSubject)
      telemetry.decodeSubject(
        subject,
        AccessTokenVerifierTelemetry.SubjectShape.from(subject),
        IO.delay(subject.flatMap(readSubject)),
      ).map { decoded =>
        for
          _         <- decoded
          issuedAt  <- Option(jwt.getIssuedAtAsInstant)
          expiresAt <- Option(jwt.getExpiresAtAsInstant)
          if issuedAt.getEpochSecond >= 0 && expiresAt.isAfter(issuedAt)
          jwtId     <- Option(jwt.getId)
          _         <- parseUuid(jwtId)
          principal <- readPrincipal(jwt)
        yield principal
      }
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
    new AccessTokenVerifier(
      publicKey,
      issuer,
      audience,
      readPrincipal,
      parseUuid,
      AccessTokenVerifierTelemetry.noop,
    )

  def userId(
      publicKey: RSAPublicKey,
      issuer: String,
      audience: String,
  ): AccessTokenVerifier[UUID] =
    apply(publicKey, issuer, audience)(jwt => Some(UUID.fromString(jwt.getSubject)))

  /** Used by Identity during the subject-format migration. Playback intentionally uses userId. */
  def userIdCompatible(
      publicKey: RSAPublicKey,
      issuer: String,
      audience: String,
  ): AccessTokenVerifier[UUID] =
    new AccessTokenVerifier(
      publicKey,
      issuer,
      audience,
      jwt => Option(jwt.getSubject).flatMap(compatibleSubject),
      compatibleSubject,
      AccessTokenVerifierTelemetry.noop,
    )

  private def compatibleSubject(value: String): Option[UUID] =
    parseUuid(value).orElse(
      Option.when(value.startsWith("user:"))(value.stripPrefix("user:")).flatMap(parseUuid),
    )

  private def parseUuid(value: String): Option[UUID] =
    Try(UUID.fromString(value)).toOption.filter(_.toString.equalsIgnoreCase(value))
