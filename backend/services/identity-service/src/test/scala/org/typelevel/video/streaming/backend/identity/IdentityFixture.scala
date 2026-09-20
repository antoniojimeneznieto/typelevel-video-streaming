package org.typelevel.video.streaming.backend.identity

import java.time.Instant
import java.util.UUID

import cats.effect.IO
import org.http4s.{DecodeResult, EntityDecoder, MalformedMessageBodyFailure, MediaType}
import org.typelevel.video.streaming.backend.identity.api.*
import org.typelevel.video.streaming.backend.identity.auth.*
import org.typelevel.video.streaming.backend.identity.domain.*
import org.typelevel.video.streaming.backend.runtime.postgres.SkunkSpec
import skunk.codec.all.*
import skunk.implicits.*
import skunk.{Query, Void}
import smithy4s.json.Json
import smithy4s.time.Timestamp
import smithy4s.{Blob, Schema}

trait IdentityFixture extends SkunkSpec:

  ///////////////////////////////////////////////////////////////////////////////
  // test data
  ///////////////////////////////////////////////////////////////////////////////

  private val createdAt = Timestamp.fromInstant(Instant.parse("2026-09-07T12:00:00Z"))
  protected val alice   = User(
    id           = UserId(new UUID(0L, 1L)),
    email        = valid(Email("alice@example.com")),
    passwordHash = valid(PasswordHash("test-password-hash-not-for-production")),
    displayName  = valid(DisplayName("Private Test User")),
    role         = Role.STUDENT,
    status       = UserStatus.ACTIVE,
    createdAt    = createdAt,
    updatedAt    = createdAt,
  )

  protected val registerInput = RegisterInput(
    alice.email,
    valid(NewPassword("very-secret-password")),
    alice.displayName,
  )

  protected val loginInput = LoginInput(
    alice.email,
    valid(Password(registerInput.password.value)),
  )

  protected val loginResponse = LoginResponse(
    accessToken = valid(AccessToken("test-access-token")),
    tokenType   = TokenType.BEARER,
    expiresIn   = valid(ExpiresInSeconds(7200)),
  )

  protected val userResponse  = UserResponse(alice.id, alice.email, alice.displayName, alice.role)
  protected val existingEmail = valid(Email("existing@example.com"))

  protected val claims = AccessTokenClaims(
    iss  = TokenIssuer.IDENTITY,
    sub  = alice.id,
    aud  = TokenAudience.COURSE_PLATFORM,
    role = alice.role,
    iat  = valid(JwtNumericDate(1786200000L)),
    exp  = valid(JwtNumericDate(1786207200L)),
    jti  = JwtId(UUID.fromString("978c2e02-d49f-4c0a-a76d-72448a47e88d")),
  )

  protected def user(id: Long, email: String): User = alice.copy(
    id    = UserId(new UUID(0L, id)),
    email = valid(Email(email)),
  )

  ///////////////////////////////////////////////////////////////////////////////
  // http decoding
  ///////////////////////////////////////////////////////////////////////////////

  protected given [A: Schema]: EntityDecoder[IO, A] =
    EntityDecoder.decodeBy[IO, A](MediaType.application.json) { message =>
      DecodeResult(
        message.body.compile.to(Array).map { bytes =>
          Json.read[A](Blob(bytes)).left.map { error =>
            MalformedMessageBodyFailure("Invalid JSON response", Some(error))
          }
        },
      )
    }

  ///////////////////////////////////////////////////////////////////////////////
  // database helpers
  ///////////////////////////////////////////////////////////////////////////////

  override protected val initScript: String = "sql/identity.sql"

  final protected case class OutboxRow(
      id: UUID,
      aggregateType: String,
      aggregateId: String,
      eventType: String,
      payload: String,
  )

  protected val selectOutbox: Query[Void, OutboxRow] =
    sql"SELECT id, aggregatetype, aggregateid, type, payload::text FROM outbox ORDER BY id"
      .query((uuid *: text *: text *: text *: text).to[OutboxRow])

  protected val countUsers: Query[Void, Long] =
    sql"SELECT count(*) FROM users".query(int8)

  ///////////////////////////////////////////////////////////////////////////////
  // validation helper
  ///////////////////////////////////////////////////////////////////////////////

  protected def valid[A](value: Either[String, A]): A =
    value.fold(message => throw new AssertionError(message), identity)
