package org.typelevel.video.streaming.backend.common.auth

import cats.effect.IO
import cats.syntax.all.*
import com.auth0.jwk.{JwkProvider, JwkProviderBuilder}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import org.typelevel.video.streaming.backend.common.config.KeycloakConfig

import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

trait TokenVerifier {
  def verify(token: String): IO[Either[AuthError, Caller]]
}

object TokenVerifier {

  private val ClockSkewSeconds = 30L

  def make(config: KeycloakConfig): TokenVerifier = {
    val issuer  = s"${config.issuerUrl}/realms/${config.realm}"
    val jwksUrl = s"${config.url}/realms/${config.realm}/protocol/openid-connect/certs"

    val jwkProvider = new JwkProviderBuilder(new URI(jwksUrl).toURL)
      .cached(10L, 24L, TimeUnit.HOURS)       // Cache 10 entries per 24h
      .rateLimited(10L, 1L, TimeUnit.MINUTES) // 10 fetches/min rate limit
      .build()

    new Impl(issuer, config.audience, jwkProvider)
  }

  final private class Impl(issuer: String, audience: String, provider: JwkProvider)
      extends TokenVerifier {

    def verify(token: String): IO[Either[AuthError, Caller]] =
      IO.blocking {
        val decoded: DecodedJWT = JWT.decode(token)
        val jwk                 = provider.get(decoded.getKeyId)
        val algorithm           = Algorithm.RSA256(jwk.getPublicKey.asInstanceOf[RSAPublicKey], null)

        val verified = JWT
          .require(algorithm)
          .withIssuer(issuer)
          .withAudience(audience)
          .acceptLeeway(ClockSkewSeconds)
          .build()
          .verify(token)

        toCaller(verified)
      }.attempt
        .map(_.leftMap(error => AuthError.InvalidToken(error.getMessage)))

    /////////////////////////////////////////////////////////////////////////////
    // Helpers
    /////////////////////////////////////////////////////////////////////////////

    private def toCaller(jwt: DecodedJWT): Caller =
      Caller(
        subject = jwt.getSubject,
        email = Option(jwt.getClaim("email").asString),
        preferredUsername = jwt.getClaim("preferred_username").asString,
        emailVerified = Option(jwt.getClaim("email_verified").asBoolean).exists(_.booleanValue),
        roles = realmRoles(jwt),
      )
  }

  private def realmRoles(jwt: DecodedJWT): Set[Role] =
    Option(jwt.getClaim("realm_access").asMap())
      .flatMap(values => Option(values.get("roles")))
      .collect { case roles: java.util.Collection[?] =>
        roles.asScala.collect { case role: String => role }.toList
      }
      .getOrElse(Nil)
      .flatMap(Role.fromString)
      .toSet

}
