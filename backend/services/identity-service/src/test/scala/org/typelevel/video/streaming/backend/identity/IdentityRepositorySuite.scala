package org.typelevel.video.streaming.backend.identity

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import org.typelevel.video.streaming.backend.events.UserCreated
import org.typelevel.video.streaming.backend.identity.domain.*
import org.typelevel.video.streaming.backend.identity.repository.{
  IdentityRepository,
  IdentityRepositoryImpl,
}
import skunk.codec.all.*
import skunk.implicits.*
import skunk.util.Origin
import skunk.{Command, Query, Session, SqlState, Void}
import smithy4s.Blob
import smithy4s.json.Json
import smithy4s.time.Timestamp
import weaver.{Expectations, SimpleIOSuite}

object IdentityRepositorySuite extends SimpleIOSuite:

  private given MeterProvider[IO]  = MeterProvider.noop[IO]
  private given TracerProvider[IO] = TracerProvider.noop[IO]

  private val createdAt = Timestamp.fromInstant(Instant.parse("2026-09-07T12:00:00Z"))
  private val alice     = user(1L, "alice@example.com")

  test("registration atomically stores one user and one minimal UserCreated outbox event") {
    withDatabase { database =>
      for
        created     <- database.repository.create(alice)
        stored      <- database.repository.findById(alice.id)
        events      <- database.outbox
        payloadKeys <- database.sessions.use(
                         _.execute(
                           sql"SELECT jsonb_object_keys(payload) FROM outbox".query(text),
                         ),
                       )
        count <- database.userCount
      yield expect.all(
        created.contains(alice),
        stored.contains(alice),
        count == 1L,
        events.size == 1,
        events.forall { row =>
          row.aggregateType == "user" && row.aggregateId == alice.id.value.toString &&
          row.eventType == "UserCreated" &&
          Json.read[UserCreated](Blob(row.payload)).exists { event =>
            event.eventId.value == row.id && event.userId.value == alice.id.value &&
            event.occurredAt == alice.createdAt
          }
        },
        payloadKeys.toSet == Set("eventId", "occurredAt", "userId"),
        payloadKeys.size == 3,
        events.forall(row =>
          !row.payload.contains(alice.email.value) &&
            !row.payload.contains(alice.passwordHash.value) &&
            !row.payload.contains(alice.displayName.value),
        ),
      )
    }
  }

  test("duplicate emails, including mixed-case variants, do not insert users or outbox events") {
    withDatabase { database =>
      for
        original           <- database.repository.create(alice)
        initialEvents      <- database.outbox
        exactDuplicate     <- database.repository.create(user(2L, "alice@example.com"))
        mixedCaseDuplicate <- database.repository.create(user(3L, "ALICE@EXAMPLE.COM"))
        currentEvents      <- database.outbox
        stored             <- database.repository.findByEmail(valid(Email("Alice@Example.Com")))
        count              <- database.userCount
      yield expect.all(
        original.contains(alice),
        exactDuplicate.isEmpty,
        mixedCaseDuplicate.isEmpty,
        currentEvents == initialEvents,
        currentEvents.size == 1,
        stored.contains(alice),
        count == 1L,
      )
    }
  }

  test("concurrent registrations for one email commit exactly one user and one matching event") {
    withDatabase { database =>
      val candidates = (1L to 8L).toList.map { id =>
        user(id, if id % 2L == 0L then "ALICE@EXAMPLE.COM" else "alice@example.com")
      }

      for
        results <- candidates.parTraverse(database.repository.create)
        events  <- database.outbox
        stored  <- database.repository.findByEmail(alice.email)
        count   <- database.userCount
        winners  = results.flatten
      yield expect.all(
        winners.size == 1,
        stored == winners.headOption,
        count == 1L,
        events.size == 1,
        events.forall { row =>
          winners.exists(winner => row.aggregateId == winner.id.value.toString) &&
          Json
            .read[UserCreated](Blob(row.payload))
            .exists(event =>
              winners.exists(winner => event.userId.value == winner.id.value) &&
                event.eventId.value == row.id,
            )
        },
      )
    }
  }

  test("an outbox write failure rolls back the user and leaves the connection usable for retry") {
    withDatabase { database =>
      for
        _ <- database.execute("ALTER TABLE outbox ADD CONSTRAINT reject_test_outbox CHECK (false)")
        rejected       <- database.repository.create(alice).attempt
        rolledBack     <- database.repository.findById(alice.id)
        rejectedCount  <- database.userCount
        rejectedEvents <- database.outbox
        _              <- database.execute("ALTER TABLE outbox DROP CONSTRAINT reject_test_outbox")
        retried        <- database.repository.create(alice)
        finalCount     <- database.userCount
        finalEvents    <- database.outbox
      yield expect.all(
        rejected match
          case Left(SqlState.CheckViolation(_)) => true
          case _ => false,
        rolledBack.isEmpty,
        rejectedCount == 0L,
        rejectedEvents.isEmpty,
        retried.contains(alice),
        finalCount == 1L,
        finalEvents.size == 1,
      )
    }
  }

  test(
    "find-by-ID and case-insensitive find-by-email preserve user data without publishing events",
  ) {
    withDatabase { database =>
      val bob = user(2L, "Bob@Example.com").copy(role = Role.ADMIN, status = UserStatus.DISABLED)

      for
        _             <- database.repository.create(alice)
        _             <- database.repository.create(bob)
        initialEvents <- database.outbox
        byId          <- database.repository.findById(bob.id)
        byEmail       <- database.repository.findByEmail(valid(Email("BOB@example.COM")))
        missingId     <- database.repository.findById(UserId(new UUID(0L, 99L)))
        missingEmail  <- database.repository.findByEmail(valid(Email("missing@example.com")))
        finalEvents   <- database.outbox
      yield expect.all(
        byId.contains(bob),
        byEmail.contains(bob),
        missingId.isEmpty,
        missingEmail.isEmpty,
        finalEvents == initialEvents,
        finalEvents.size == 2,
      )
    }
  }

  final private case class OutboxRow(
      id: UUID,
      aggregateType: String,
      aggregateId: String,
      eventType: String,
      payload: String,
  )

  private val selectOutbox: Query[Void, OutboxRow] =
    sql"SELECT id, aggregatetype, aggregateid, type, payload::text FROM outbox ORDER BY id"
      .query((uuid *: text *: text *: text *: text).to[OutboxRow])

  final private case class Database(
      repository: IdentityRepository,
      sessions: Resource[IO, Session[IO]],
  ):
    def outbox: IO[List[OutboxRow]] = sessions.use(_.execute(selectOutbox))
    def userCount: IO[Long] = sessions.use(_.unique(sql"SELECT count(*) FROM users".query(int8)))
    def execute(statement: String): IO[Unit] = sessions.use(executeStatement(_, statement))

  private def withDatabase(run: Database => IO[Expectations]): IO[Expectations] =
    if sys.env.get("IDENTITY_REPOSITORY_TESTS").contains("true") then isolatedDatabase.use(run)
    else
      ignore[IO](
        "Postgres integration test: enable with IDENTITY_REPOSITORY_TESTS=true " +
          "sbt 'identityService/testOnly *IdentityRepositorySuite'",
      )

  private def isolatedDatabase: Resource[IO, Database] =
    for
      statements <- Resource.eval(identitySchema)
      schema     <- Resource.make(
                  IO(UUID.randomUUID())
                    .map(id => s"identity_repository_test_${id.toString.replace("-", "")}")
                    .flatTap(name => adminCommand(s"CREATE SCHEMA ${schemaIdentifier(name)}")),
                )(name => adminCommand(s"DROP SCHEMA ${schemaIdentifier(name)} CASCADE"))
      sessions <-
        connection
          .withConnectionParameters(Session.DefaultConnectionParameters + ("search_path" -> schema))
          .pooled(4)
      _ <-
        Resource.eval(sessions.use(session => statements.traverse_(executeStatement(session, _))))
    yield Database(new IdentityRepositoryImpl(sessions), sessions)

  private def connection: Session.Builder[IO] =
    Session
      .Builder[IO]
      .withHost(sys.env.getOrElse("IDENTITY_TEST_POSTGRES_HOST", "localhost"))
      .withPort(sys.env.getOrElse("IDENTITY_TEST_POSTGRES_PORT", "5432").toInt)
      .withUserAndPassword(
        sys.env.getOrElse("IDENTITY_TEST_POSTGRES_USER", "postgres"),
        sys.env.getOrElse("IDENTITY_TEST_POSTGRES_PASSWORD", "postgres"),
      )
      .withDatabase(sys.env.getOrElse("IDENTITY_TEST_POSTGRES_DATABASE", "postgres"))

  private def schemaIdentifier(name: String): String =
    require(name.matches("identity_repository_test_[a-f0-9]{32}"), "Invalid isolated test schema")
    s"\"$name\""

  private def adminCommand(statement: String): IO[Unit] =
    connection.single.use(executeStatement(_, statement))

  private def executeStatement(session: Session[IO], statement: String): IO[Unit] =
    session.execute(Command(statement, Origin.unknown, Void.codec)).void

  private def identitySchema: IO[List[String]] = IO.blocking {
    val relative = Path.of("infrastructure", "postgres", "init", "01-schema.sql")
    val source   = Iterator
      .iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve(relative))
      .find(path => Files.isRegularFile(path))
      .getOrElse(
        throw new IllegalStateException("Cannot locate the repository's Postgres init SQL"),
      )
    val sql         = Files.readString(source)
    val usersStart  = sql.indexOf("CREATE TABLE users (")
    val outboxStart = sql.indexOf("-- Identity outbox")
    val outboxEnd   = sql.indexOf("-- End Identity outbox", outboxStart)
    require(
      usersStart >= 0 && outboxStart > usersStart && outboxEnd > outboxStart,
      "Cannot locate the isolated Identity users/outbox schema sections",
    )
    val ddl = sql
      .substring(usersStart, outboxEnd)
      .linesIterator
      .filterNot(_.trim.startsWith("--"))
      .mkString("\n")
      .split(";")
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList
    require(
      ddl.nonEmpty && ddl.forall(statement =>
        statement.startsWith("CREATE TABLE ") || statement.startsWith("CREATE UNIQUE INDEX ") ||
          statement.startsWith("CREATE INDEX "),
      ),
      "Expected Identity table/index DDL only; refusing role or publication changes",
    )
    ddl
  }

  private def user(id: Long, email: String): User = User(
    id           = UserId(new UUID(0L, id)),
    email        = valid(Email(email)),
    passwordHash = valid(PasswordHash("test-password-hash-not-for-production")),
    displayName  = valid(DisplayName("Private Test User")),
    role         = Role.STUDENT,
    status       = UserStatus.ACTIVE,
    createdAt    = createdAt,
    updatedAt    = createdAt,
  )

  private def valid[A](value: Either[String, A]): A =
    value.fold(message => throw new AssertionError(message), identity)
