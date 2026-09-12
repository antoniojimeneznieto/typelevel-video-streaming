package org.typelevel.video.streaming.backend.identity.service

import org.typelevel.video.streaming.backend.identity.domain.{NewPassword, Password}
import weaver.SimpleIOSuite

object PasswordHasherSuite extends SimpleIOSuite:

  private val hasher        = PasswordHasherImpl()
  private val newPassword   = valid(NewPassword("very-secret-password"))
  private val password      = valid(Password("very-secret-password"))
  private val wrongPassword = valid(Password("wrong-password"))

  test("Argon2id hashes verify only the original password") {
    for
      hash         <- hasher.hash(newPassword)
      matches      <- hasher.verify(password, hash)
      wrongMatches <- hasher.verify(wrongPassword, hash)
    yield expect(matches) and expect(!wrongMatches)
  }

  test("each password hash contains a unique salt") {
    for
      first  <- hasher.hash(newPassword)
      second <- hasher.hash(newPassword)
    yield expect(first != second)
  }

  private def valid[A](either: Either[String, A]): A =
    either.fold(message => throw new AssertionError(message), identity)
