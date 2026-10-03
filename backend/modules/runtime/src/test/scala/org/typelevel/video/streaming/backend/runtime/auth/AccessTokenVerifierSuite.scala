package org.typelevel.video.streaming.backend.runtime.auth

import java.nio.file.Files
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.security.{KeyPair, KeyPairGenerator}
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.{Base64, UUID}

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.{JWT, JWTCreator}
import weaver.SimpleIOSuite

object AccessTokenVerifierSuite extends SimpleIOSuite:

  ///////////////////////////////////////////////////////////////////////////////
  // preparation
  ///////////////////////////////////////////////////////////////////////////////

  private val userId   = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
  private val jwtId    = "978c2e02-d49f-4c0a-a76d-72448a47e88d"
  private val issuer   = "identity"
  private val audience = "course-platform"

  final private case class Principal(userId: UUID, role: String)

  private def claims(now: Instant): JWTCreator.Builder =
    JWT
      .create()
      .withIssuer("identity")
      .withAudience("course-platform")
      .withSubject(userId.toString)
      .withClaim("role", "student")
      .withIssuedAt(now.minusSeconds(60))
      .withExpiresAt(now.plusSeconds(1800))
      .withJWTId(jwtId)

  private def keys: IO[KeyPair] =
    IO.blocking {
      val generator = KeyPairGenerator.getInstance("RSA")
      generator.initialize(2048)
      generator.generateKeyPair()
    }

  private def publicKey(pair: KeyPair): RSAPublicKey =
    pair.getPublic.asInstanceOf[RSAPublicKey]

  private def algorithm(pair: KeyPair): Algorithm =
    Algorithm.RSA256(publicKey(pair), pair.getPrivate.asInstanceOf[RSAPrivateKey])

  ///////////////////////////////////////////////////////////////////////////////
  // tests
  ///////////////////////////////////////////////////////////////////////////////

  test("a valid Identity RS256 access token yields its user ID") {
    keys.flatMap { pair =>
      val token = claims(Instant.now()).sign(algorithm(pair))

      AccessTokenVerifier
        .userId(publicKey(pair), issuer, audience)
        .verify(token)
        .map(result => expect(result.contains(userId)))
    }
  }

  test("the migration decoder accepts only raw and user-prefixed UUID subjects") {
    keys.flatMap { pair =>
      val now      = Instant.now()
      val subjects =
        List(userId.toString, s"user:$userId", "user:bad", s"admin:$userId", s"user:user:$userId")
      val tokens = subjects.map(subject => claims(now).withSubject(subject).sign(algorithm(pair)))
      val compatible = AccessTokenVerifier.userIdCompatible(publicKey(pair), issuer, audience)
      val legacy     = AccessTokenVerifier.userId(publicKey(pair), issuer, audience)
      for
        migrated <- tokens.traverse(compatible.verify)
        old      <- tokens.traverse(legacy.verify)
      yield expect.all(
        migrated == List(Some(userId), Some(userId), None, None, None),
        old == List(Some(userId), None, None, None, None),
      )
    }
  }

  test("invalid claims and missing required claims are rejected") {
    keys.flatMap { pair =>
      val now           = Instant.now()
      val invalidClaims = List[JWTCreator.Builder => JWTCreator.Builder](
        _.withIssuer("another-service"),
        _.withAudience("another-platform"),
        _.withSubject("not-a-uuid"),
        _.withSubject("1-1-1-1-1"),
        _.withJWTId("not-a-uuid"),
        _.withJWTId("1-1-1-1-1"),
        _.withIssuedAt(now.plusSeconds(120)),
        _.withIssuedAt(Instant.ofEpochSecond(-1)),
        _.withExpiresAt(now.minusSeconds(120)),
        _.withClaim("exp", "not-a-number"),
      )
      val nullClaims = List("sub", "iat", "exp", "jti").map {
        name => (builder: JWTCreator.Builder) => builder.withNullClaim(name)
      }
      val tokens = (invalidClaims ++ nullClaims).map(change =>
        change(claims(now)).sign(algorithm(pair)),
      ) :+ JWT
        .create()
        .withIssuer("identity")
        .withAudience("course-platform")
        .withSubject(userId.toString)
        .withIssuedAt(now.minusSeconds(60))
        .withJWTId(jwtId)
        .sign(algorithm(pair))

      List(
        AccessTokenVerifier.userId(publicKey(pair), issuer, audience),
        AccessTokenVerifier.userIdCompatible(publicKey(pair), issuer, audience),
      ).traverse(verifier => tokens.traverse(verifier.verify)).map { results =>
        expect(results.forall(_.forall(_.isEmpty)))
      }
    }
  }

  test("malformed tokens, a different signature, and a different algorithm are rejected") {
    (keys, keys).tupled.flatMap { case (trusted, untrusted) =>
      val now    = Instant.now()
      val tokens = List(
        "not-a-jwt",
        claims(now).sign(algorithm(untrusted)),
        claims(now).sign(Algorithm.HMAC256("untrusted-secret")),
        claims(now).sign(Algorithm.none()),
      )

      List(
        AccessTokenVerifier.userId(publicKey(trusted), issuer, audience),
        AccessTokenVerifier.userIdCompatible(publicKey(trusted), issuer, audience),
      ).traverse(verifier => tokens.traverse(verifier.verify)).map { results =>
        expect(results.forall(_.forall(_.isEmpty)))
      }
    }
  }

  test("an X509 PEM public key can be loaded without a private key") {
    keys.flatMap { pair =>
      val pem = "-----BEGIN PUBLIC KEY-----\n" +
        Base64.getMimeEncoder(64, Array('\n'.toByte)).encodeToString(publicKey(pair).getEncoded) +
        "\n-----END PUBLIC KEY-----\n"

      Resource
        .make(IO.blocking(Files.createTempFile("runtime-public-key-", ".pem")))(path =>
          IO.blocking(Files.deleteIfExists(path)).void,
        )
        .use { path =>
          for
            _       <- IO.blocking(Files.writeString(path, pem))
            key     <- RsaKeyLoader.publicKey(path)
            verifier = AccessTokenVerifier.userId(key, issuer, audience)
            result  <- verifier.verify(claims(Instant.now()).sign(algorithm(pair)))
          yield expect(result.contains(userId))
        }
    }
  }

  test("invalid public key configuration fails instead of becoming an authentication failure") {
    Resource
      .make(IO.blocking(Files.createTempFile("runtime-invalid-public-key-", ".pem")))(path =>
        IO.blocking(Files.deleteIfExists(path)).void,
      )
      .use { path =>
        RsaKeyLoader.publicKey(path).attempt.map(result => expect(result.isLeft))
      }
  }

  test("each service can decode its own principal and reject unsupported or missing roles") {
    keys.flatMap { pair =>
      val verifier = AccessTokenVerifier[Principal](publicKey(pair), issuer, audience) { jwt =>
        Option(jwt.getClaim("role").asString())
          .filter(role => role == "student" || role == "admin")
          .map(role => Principal(UUID.fromString(jwt.getSubject), role))
      }
      val tokens = List(
        claims(Instant.now()).withClaim("role", "student"),
        claims(Instant.now()).withClaim("role", "admin"),
        claims(Instant.now()).withClaim("role", "unsupported"),
        claims(Instant.now()).withNullClaim("role"),
      ).map(_.sign(algorithm(pair)))

      tokens.traverse(verifier.verify).map { results =>
        expect(
          results == List(
            Some(Principal(userId, "student")),
            Some(Principal(userId, "admin")),
            None,
            None,
          ),
        )
      }
    }
  }

  test("the custom principal reader is not called for unverified or invalid standard claims") {
    (keys, keys).tupled.flatMap { case (trusted, untrusted) =>
      val invocations = new AtomicInteger()
      val verifier    = AccessTokenVerifier[UUID](publicKey(trusted), issuer, audience) { _ =>
        invocations.incrementAndGet()
        Some(userId)
      }
      val now    = Instant.now()
      val tokens = List(
        "not-a-jwt",
        claims(now).sign(algorithm(untrusted)),
        claims(now).withIssuer("unexpected").sign(algorithm(trusted)),
        claims(now).withSubject("1-1-1-1-1").sign(algorithm(trusted)),
        claims(now).withJWTId("not-a-uuid").sign(algorithm(trusted)),
        claims(now).withExpiresAt(now.minusSeconds(120)).sign(algorithm(trusted)),
      )

      tokens.traverse(verifier.verify).map { results =>
        expect.all(results.forall(_.isEmpty), invocations.get() == 0)
      }
    }
  }

  test("unexpected principal reader failures propagate rather than hiding application bugs") {
    keys.flatMap { pair =>
      val failure  = new IllegalStateException("Principal decoder failed")
      val verifier =
        AccessTokenVerifier[UUID](publicKey(pair), issuer, audience)(_ => throw failure)

      verifier
        .verify(claims(Instant.now()).sign(algorithm(pair)))
        .attempt
        .map(result => expect(result == Left(failure)))
    }
  }
