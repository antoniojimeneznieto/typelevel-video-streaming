package org.typelevel.video.streaming.backend.identity

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.util.UUID

import cats.effect.IO
import cats.syntax.all.*
import com.auth0.jwt.JWT
import org.typelevel.video.streaming.backend.identity.api.{AccessToken, ExpiresInSeconds}
import org.typelevel.video.streaming.backend.identity.domain.{Email, Role, UserId}
import org.typelevel.video.streaming.backend.identity.service.AccessTokenIssuerImpl
import weaver.SimpleIOSuite

object AccessTokenIssuerSuite extends SimpleIOSuite:
  test("only the workshop migration accounts receive prefixed subjects") {
    IO.blocking {
      val generator = KeyPairGenerator.getInstance("RSA")
      generator.initialize(2048)
      generator.generateKeyPair().getPrivate.asInstanceOf[RSAPrivateKey]
    }.flatMap { key =>
      val id        = UserId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"))
      val lifetime  = ExpiresInSeconds(7200).toOption.get
      val issuer    = AccessTokenIssuerImpl(key, lifetime, workshopSubjectMigration = true)
      val disabled  = AccessTokenIssuerImpl(key, lifetime)
      val old       = Email("lab-playback-old-0@example.invalid").toOption.get
      val modern    = Email("lab-playback-new-8@example.invalid").toOption.get
      val unrelated = Email("alice@example.com").toOption.get
      List(
        issuer.issue(id, Role.STUDENT, old),
        issuer.issue(id, Role.STUDENT, modern),
        issuer.issue(id, Role.STUDENT, unrelated),
        disabled.issue(id, Role.STUDENT, modern),
      ).sequence.map { issued =>
        val subjects =
          issued.map(token => JWT.decode(AccessToken.value(token.accessToken)).getSubject)
        val raw = UserId.value(id).toString
        expect(subjects == List(raw, s"user:$raw", raw, raw))
      }
    }
  }
