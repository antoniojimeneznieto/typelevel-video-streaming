package org.typelevel.video.streaming.backend.identity.service

import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.UUID

import cats.effect.IO
import cats.syntax.all.*
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.JWT
import org.typelevel.video.streaming.backend.identity.api.ExpiresInSeconds
import org.typelevel.video.streaming.backend.identity.auth.{TokenAudience, TokenIssuer}
import org.typelevel.video.streaming.backend.identity.domain.{Role, UserId}
import org.typelevel.video.streaming.backend.runtime.auth.AccessTokenVerifier
import weaver.SimpleIOSuite

object AccessTokenIssuerSuite extends SimpleIOSuite:

  private val userId    = UserId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"))
  private val jwtId     = UUID.fromString("978c2e02-d49f-4c0a-a76d-72448a47e88d")
  private val issuedAt  = Instant.ofEpochSecond(1786200000L)
  private val expiresIn = valid(ExpiresInSeconds(7200))

  test("an issued token is valid for two hours and has the modeled claims and RS256 signature") {
    IO.blocking {
      val generator = KeyPairGenerator.getInstance("RSA")
      generator.initialize(2048)
      generator.generateKeyPair()
    }.flatMap { keyPair =>
      val privateKey = keyPair.getPrivate.asInstanceOf[RSAPrivateKey]
      val publicKey  = keyPair.getPublic.asInstanceOf[RSAPublicKey]
      val issuer     = new AccessTokenIssuerImpl(
        privateKey     = privateKey,
        expiresIn      = expiresIn,
        currentInstant = IO.pure(issuedAt),
        newJwtId       = IO.pure(jwtId)
      )

      issuer.issue(userId, Role.STUDENT).map { issued =>
        val algorithm = Algorithm.RSA256(publicKey, null)
        val decoded   = JWT.decode(issued.accessToken.value)
        algorithm.verify(decoded)

        expect(decoded.getAlgorithm == "RS256") and
          expect(decoded.getIssuer == "identity") and
          expect(decoded.getAudience.contains("course-platform")) and
          expect(decoded.getSubject == userId.value.toString) and
          expect(decoded.getClaim("role").asString() == "student") and
          expect(decoded.getIssuedAtAsInstant == issuedAt) and
          expect(decoded.getExpiresAtAsInstant == issuedAt.plusSeconds(7200)) and
          expect(decoded.getId == jwtId.toString) and
          expect(issued.expiresIn == expiresIn)
      }
    }
  }

  test("an issued token can be verified into modeled access token claims") {
    IO.blocking {
      val generator = KeyPairGenerator.getInstance("RSA")
      generator.initialize(2048)
      generator.generateKeyPair()
    }.flatMap { keyPair =>
      val privateKey = keyPair.getPrivate.asInstanceOf[RSAPrivateKey]
      val publicKey  = keyPair.getPublic.asInstanceOf[RSAPublicKey]
      val now        = Instant.now()
      val issuer     = new AccessTokenIssuerImpl(
        privateKey     = privateKey,
        expiresIn      = expiresIn,
        currentInstant = IO.pure(now),
        newJwtId       = IO.pure(jwtId)
      )
      val verifier        = AccessTokenVerifierImpl(publicKey)
      val subjectVerifier = AccessTokenVerifier.userId(publicKey, "identity", "course-platform")

      for
        issued  <- issuer.issue(userId, Role.STUDENT)
        claims  <- verifier.verify(issued.accessToken.value)
        subject <- subjectVerifier.verify(issued.accessToken.value)
      yield claims match
        case Some(value) =>
          expect(subject.contains(userId.value)) and
            expect(value.iss == TokenIssuer.IDENTITY) and
            expect(value.sub == userId) and
            expect(value.aud == TokenAudience.COURSE_PLATFORM) and
            expect(value.role == Role.STUDENT) and
            expect(value.iat.value == now.getEpochSecond) and
            expect(value.exp.value == now.plusSeconds(7200).getEpochSecond) and
            expect(value.jti.value == jwtId)
        case None => failure("the issued token was not verified")
    }
  }

  test("an invalid token is rejected") {
    IO.blocking {
      val generator = KeyPairGenerator.getInstance("RSA")
      generator.initialize(2048)
      generator.generateKeyPair().getPublic.asInstanceOf[RSAPublicKey]
    }.flatMap { publicKey =>
      AccessTokenVerifierImpl(publicKey)
        .verify("not-a-jwt")
        .map(result => expect(result.isEmpty))
    }
  }

  test("Identity's claims reader rejects missing and unknown roles") {
    IO.blocking {
      val generator = KeyPairGenerator.getInstance("RSA")
      generator.initialize(2048)
      generator.generateKeyPair()
    }.flatMap { pair =>
      val publicKey = pair.getPublic.asInstanceOf[RSAPublicKey]
      val algorithm = Algorithm.RSA256(publicKey, pair.getPrivate.asInstanceOf[RSAPrivateKey])
      val verifier  = AccessTokenVerifierImpl(publicKey)
      val now       = Instant.now()

      List(None, Some("unknown"), Some("admin"))
        .traverse { role =>
          val builder = JWT
            .create()
            .withIssuer("identity")
            .withAudience("course-platform")
            .withSubject(userId.value.toString)
            .withIssuedAt(now.minusSeconds(1))
            .withExpiresAt(now.plusSeconds(1800))
            .withJWTId(jwtId.toString)
          role.foreach(value => builder.withClaim("role", value))
          verifier.verify(builder.sign(algorithm))
        }
        .map { results =>
          expect.all(
            results.take(2).forall(_.isEmpty),
            results.last.exists(_.role == Role.ADMIN)
          )
        }
    }
  }

  private def valid[A](either: Either[String, A]): A =
    either.fold(message => throw new AssertionError(message), identity)
