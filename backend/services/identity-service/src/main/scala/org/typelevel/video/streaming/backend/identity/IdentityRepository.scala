package org.typelevel.video.streaming.backend.identity.repository

import java.util.UUID

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import org.typelevel.video.streaming.backend.events as event
import org.typelevel.video.streaming.backend.identity.domain.*
import skunk.codec.all.*
import skunk.implicits.*
import skunk.{Codec, Command, Query, Session}
import smithy4s.json.Json
import smithy4s.time.Timestamp
import IdentityRepositoryImpl.*

trait IdentityRepository:

  def create(user: User): IO[Option[User]]

  def findById(id: UserId): IO[Option[User]]

  def findByEmail(email: Email): IO[Option[User]]

final class IdentityRepositoryImpl(
    sessions: Resource[IO, Session[IO]],
) extends IdentityRepository:

  override def create(user: User): IO[Option[User]] =
    sessions.use { session =>
      session.transaction.use { _ =>
        session
          .option(insertUser)(user)
          .flatMap(
            _.traverse { created =>
              for
                id          <- IO(UUID.randomUUID())
                createdEvent = event.UserCreated(
                                 eventId    = event.EventId(id),
                                 occurredAt = created.createdAt,
                                 userId     = event.UserId(created.id.value),
                               )
                _ <- session.execute(insertUserCreated)(createdEvent)
              yield created
            },
          )
      }
    }

  override def findById(id: UserId): IO[Option[User]] =
    sessions.use(_.option(selectById)(id))

  override def findByEmail(email: Email): IO[Option[User]] =
    sessions.use(_.option(selectByEmail)(email))

object IdentityRepositoryImpl:

  private val userId: Codec[UserId] =
    uuid.imap(UserId(_))(UserId.value)

  private val email: Codec[Email] =
    text.eimap(Email(_))(Email.value).redacted

  private val passwordHash: Codec[PasswordHash] =
    text.eimap(PasswordHash(_))(PasswordHash.value).redacted

  private val displayName: Codec[DisplayName] =
    text.eimap(DisplayName(_))(DisplayName.value).redacted

  private val role: Codec[Role] =
    text.eimap(value => enumValue("role", value, Role.values))(_.stringValue)

  private val userStatus: Codec[UserStatus] =
    text.eimap(value => enumValue("user status", value, UserStatus.values))(_.stringValue)

  private val timestamp: Codec[Timestamp] =
    timestamptz.imap(Timestamp.fromOffsetDateTime)(_.toOffsetDateTime)

  private val user: Codec[User] =
    (
      userId *:
        email *:
        passwordHash *:
        displayName *:
        role *:
        userStatus *:
        timestamp *:
        timestamp
    ).to[User]

  private val insertUser: Query[User, User] =
    sql"""
      INSERT INTO users (
        id,
        email,
        password_hash,
        display_name,
        role,
        status,
        created_at,
        updated_at
      )
      VALUES ($user)
      ON CONFLICT (lower(email)) DO NOTHING
      RETURNING
        id,
        email,
        password_hash,
        display_name,
        role,
        status,
        created_at,
        updated_at
    """.query(user)

  private val insertUserCreated: Command[event.UserCreated] =
    sql"""
      INSERT INTO outbox (id, aggregatetype, aggregateid, type, payload)
      VALUES ($uuid, 'user', $text, 'UserCreated', ${text.redacted}::jsonb)
    """.command.contramap { created =>
      (
        created.eventId.value,
        created.userId.value.toString,
        Json.writeBlob(created).toUTF8String,
      )
    }

  private val selectById: Query[UserId, User] =
    sql"""
      SELECT
        id,
        email,
        password_hash,
        display_name,
        role,
        status,
        created_at,
        updated_at
      FROM users
      WHERE id = $userId
    """.query(user)

  private val selectByEmail: Query[Email, User] =
    sql"""
      SELECT
        id,
        email,
        password_hash,
        display_name,
        role,
        status,
        created_at,
        updated_at
      FROM users
      WHERE lower(email) = lower($email)
    """.query(user)

  private def enumValue[A <: smithy4s.Enumeration.Value](
      name: String,
      value: String,
      values: List[A],
  ): Either[String, A] =
    values
      .find(_.stringValue == value)
      .toRight(s"Unknown $name: $value")
