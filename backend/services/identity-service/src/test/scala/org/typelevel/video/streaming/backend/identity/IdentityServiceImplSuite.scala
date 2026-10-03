package org.typelevel.video.streaming.backend.identity.service

import java.util.UUID

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import org.typelevel.video.streaming.backend.events.UserCreated
import org.typelevel.video.streaming.backend.identity.api.*
import org.typelevel.video.streaming.backend.identity.domain.*
import org.typelevel.video.streaming.backend.identity.IdentityFixture
import org.typelevel.video.streaming.backend.identity.repository.{
  IdentityRepository,
  IdentityRepositoryImpl,
}
import org.typelevel.video.streaming.backend.runtime.context.IOLocalRequestContext
import skunk.codec.all.{text, uuid}
import skunk.implicits.*
import skunk.{Session, SqlState}
import smithy4s.Blob
import smithy4s.json.Json
import weaver.SimpleIOSuite

object IdentityServiceImplSuite extends SimpleIOSuite with IdentityFixture:

  ///////////////////////////////////////////////////////////////////////////////
  // preparation
  ///////////////////////////////////////////////////////////////////////////////

  private val tokenIssuer: AccessTokenIssuer = new AccessTokenIssuer:
    override def issue(userId: UserId, role: Role): IO[IssuedAccessToken] =
      IO.pure(IssuedAccessToken(loginResponse.accessToken, loginResponse.expiresIn))

  private def serviceWithDatabase: Resource[
    IO,
    (IdentityService[IO], IdentityRepository, Session[IO], IOLocalRequestContext[UUID]),
  ] =
    for
      sessions  <- sessionPool
      session   <- sessions
      context   <- Resource.eval(IOLocalRequestContext.create[UUID])
      hasher    <- Resource.eval(PasswordHasherImpl.create())
      repository = new IdentityRepositoryImpl(sessions)
      service    = new IdentityServiceImpl(repository, hasher, tokenIssuer, context)
    yield (service, repository, session, context)

  ///////////////////////////////////////////////////////////////////////////////
  // tests
  ///////////////////////////////////////////////////////////////////////////////

  test("registration stores an active student with an email and one UserCreated event") {
    serviceWithDatabase.use { (service, repository, session, _) =>
      val input = registerInput.copy(email = valid(Email("Alice@Example.com")))

      for
        created     <- service.register(input.email, input.password, input.displayName)
        stored      <- repository.findById(created.id)
        byEmail     <- repository.findByEmail(input.email)
        events      <- session.execute(selectOutbox)
        payloadKeys <- session.execute(
                         sql"SELECT jsonb_object_keys(payload) FROM outbox".query(text),
                       )
        count <- session.unique(countUsers)
      yield expect.all(
        created == userResponse.copy(id = created.id),
        stored.exists { user =>
          user.id == created.id && user.email == alice.email &&
          user.passwordHash.value != input.password.value &&
          user.displayName == alice.displayName && user.role == Role.STUDENT &&
          user.status == UserStatus.ACTIVE && user.createdAt == user.updatedAt
        },
        byEmail == stored,
        count == 1L,
        events.size == 1,
        events.forall { row =>
          row.aggregateType == "user" && row.aggregateId == created.id.value.toString &&
          row.eventType == "UserCreated" &&
          Json.read[UserCreated](Blob(row.payload)).exists { event =>
            event.eventId.value == row.id && event.userId.value == created.id.value &&
            stored.exists(_.createdAt == event.occurredAt)
          }
        },
        payloadKeys.toSet == Set("eventId", "occurredAt", "userId"),
        payloadKeys.size == 3,
      )
    }
  }

  test("duplicate emails return a conflict without inserting users or outbox events") {
    serviceWithDatabase.use { (service, repository, session, _) =>
      for
        original <- service.register(
                      registerInput.email,
                      registerInput.password,
                      registerInput.displayName,
                    )
        initialEvents  <- session.execute(selectOutbox)
        exactDuplicate <- service
                            .register(
                              registerInput.email,
                              registerInput.password,
                              registerInput.displayName,
                            )
                            .attempt
        mixedCaseDuplicate <- service
                                .register(
                                  valid(Email("ALICE@EXAMPLE.COM")),
                                  registerInput.password,
                                  registerInput.displayName,
                                )
                                .attempt
        currentEvents <- session.execute(selectOutbox)
        stored        <- repository.findByEmail(valid(Email("Alice@Example.Com")))
        count         <- session.unique(countUsers)
        expected       = Left(ConflictError(ConflictErrorCode.EMAIL_ALREADY_EXISTS))
      yield expect.all(
        exactDuplicate == expected,
        mixedCaseDuplicate == expected,
        currentEvents == initialEvents,
        currentEvents.size == 1,
        stored.exists(_.id == original.id),
        count == 1L,
      )
    }
  }

  test("concurrent registrations commit exactly one user and one matching event") {
    serviceWithDatabase.use { (service, repository, session, _) =>
      for
        results <- (1 to 8).toList.parTraverse { index =>
                     val email =
                       if index % 2 == 0 then valid(Email("ALICE@EXAMPLE.COM"))
                       else registerInput.email
                     service
                       .register(email, registerInput.password, registerInput.displayName)
                       .attempt
                   }
        events <- session.execute(selectOutbox)
        stored <- repository.findByEmail(alice.email)
        count  <- session.unique(countUsers)
        winners = results.collect { case Right(user) => user }
      yield expect.all(
        winners.size == 1,
        results.count(_ == Left(ConflictError(ConflictErrorCode.EMAIL_ALREADY_EXISTS))) == 7,
        stored.map(_.id) == winners.headOption.map(_.id),
        count == 1L,
        events.size == 1,
        events.forall { row =>
          winners.exists(winner => row.aggregateId == winner.id.value.toString) &&
          Json.read[UserCreated](Blob(row.payload)).exists { event =>
            winners.exists(winner => event.userId.value == winner.id.value) &&
            event.eventId.value == row.id
          }
        },
      )
    }
  }

  test("an outbox write failure rolls back registration and allows a retry") {
    serviceWithDatabase.use { (service, repository, session, _) =>
      for
        _ <- session.execute(
               sql"ALTER TABLE outbox ADD CONSTRAINT reject_test_outbox CHECK (false)".command,
             )
        rejected <- service
                      .register(
                        registerInput.email,
                        registerInput.password,
                        registerInput.displayName,
                      )
                      .attempt
        rolledBack     <- repository.findByEmail(registerInput.email)
        rejectedCount  <- session.unique(countUsers)
        rejectedEvents <- session.execute(selectOutbox)
        _ <- session.execute(sql"ALTER TABLE outbox DROP CONSTRAINT reject_test_outbox".command)
        retried <- service.register(
                     registerInput.email,
                     registerInput.password,
                     registerInput.displayName,
                   )
        stored      <- repository.findById(retried.id)
        finalCount  <- session.unique(countUsers)
        finalEvents <- session.execute(selectOutbox)
      yield expect.all(
        rejected match
          case Left(SqlState.CheckViolation(_)) => true
          case _ => false,
        rolledBack.isEmpty,
        rejectedCount == 0L,
        rejectedEvents.isEmpty,
        stored.exists(_.id == retried.id),
        finalCount == 1L,
        finalEvents.size == 1,
      )
    }
  }

  test("login finds a registered user case-insensitively and returns a token without new events") {
    serviceWithDatabase.use { (service, _, session, _) =>
      for
        _ <- service.register(
               registerInput.email,
               registerInput.password,
               registerInput.displayName,
             )
        initialEvents <- session.execute(selectOutbox)
        response      <- service.login(valid(Email("ALICE@EXAMPLE.COM")), loginInput.password)
        finalEvents   <- session.execute(selectOutbox)
      yield expect.all(
        response == loginResponse,
        initialEvents.size == 1,
        finalEvents == initialEvents,
      )
    }
  }

  test("login returns the same error for missing users, wrong passwords, and disabled users") {
    serviceWithDatabase.use { (service, _, session, _) =>
      for
        missing <- service.login(loginInput.email, loginInput.password).attempt
        created <- service.register(
                     registerInput.email,
                     registerInput.password,
                     registerInput.displayName,
                   )
        initialEvents   <- session.execute(selectOutbox)
        invalidPassword <- service
                             .login(loginInput.email, valid(Password("wrong-password")))
                             .attempt
        _ <- session.execute(
               sql"UPDATE users SET status = 'disabled' WHERE id = $uuid".command,
             )(created.id.value)
        disabled    <- service.login(loginInput.email, loginInput.password).attempt
        finalEvents <- session.execute(selectOutbox)
        expected     = Left(InvalidCredentialsError(AuthenticationErrorCode.INVALID_CREDENTIALS))
      yield expect.all(
        missing == expected,
        invalidPassword == expected,
        disabled == expected,
        finalEvents == initialEvents,
      )
    }
  }

  test(
    "current user uses the authenticated ID and database reads preserve data without new events",
  ) {
    serviceWithDatabase.use { (service, repository, session, context) =>
      val bob = user(2L, "Bob@Example.com").copy(role = Role.ADMIN, status = UserStatus.DISABLED)

      for
        _             <- repository.create(alice)
        _             <- repository.create(bob)
        initialEvents <- session.execute(selectOutbox)
        response      <- context.scope(bob.id.value)(service.getCurrentUser())
        byId          <- repository.findById(bob.id)
        byEmail       <- repository.findByEmail(valid(Email("BOB@example.COM")))
        missingId     <- repository.findById(UserId(new UUID(0L, 99L)))
        missingEmail  <- repository.findByEmail(valid(Email("missing@example.com")))
        finalEvents   <- session.execute(selectOutbox)
      yield expect.all(
        response == userResponse.copy(id = bob.id, email = bob.email, role = bob.role),
        byId.contains(bob),
        byEmail.contains(bob),
        missingId.isEmpty,
        missingEmail.isEmpty,
        finalEvents == initialEvents,
        finalEvents.size == 2,
      )
    }
  }
