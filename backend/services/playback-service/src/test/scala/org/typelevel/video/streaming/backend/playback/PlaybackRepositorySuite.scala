package org.typelevel.video.streaming.backend.playback

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import org.typelevel.video.streaming.backend.events as event
import org.typelevel.video.streaming.backend.playback.api.PlaybackUrlResponse
import org.typelevel.video.streaming.backend.playback.domain.*
import org.typelevel.video.streaming.backend.playback.repository.{
  PlaybackProjectionRepositoryImpl,
  PlaybackRepository,
  PlaybackRepositoryImpl
}
import org.typelevel.video.streaming.backend.playback.service.PlaybackServiceImpl
import org.typelevel.video.streaming.backend.playback.storage.S3VideoStorage
import org.typelevel.video.streaming.backend.runtime.context.IOLocalRequestContext
import skunk.util.Origin
import skunk.{Command, Session, SqlState, Void}
import smithy4s.time.Timestamp
import weaver.{Expectations, SimpleIOSuite}

object PlaybackRepositorySuite extends SimpleIOSuite:

  private given MeterProvider[IO]  = MeterProvider.noop[IO]
  private given TracerProvider[IO] = TracerProvider.noop[IO]

  private val alice         = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val bob           = UUID.fromString("00000000-0000-0000-0000-000000000002")
  private val unknownUser   = UUID.fromString("00000000-0000-0000-0000-000000000099")
  private val courseA       = CourseId(UUID.fromString("00000000-0000-0000-0000-000000000101"))
  private val courseB       = CourseId(UUID.fromString("00000000-0000-0000-0000-000000000102"))
  private val unknownCourse = CourseId(UUID.fromString("00000000-0000-0000-0000-000000000999"))
  private val lessonOne     = valid(LessonId("lesson-1"))
  private val lessonTwo     = valid(LessonId("lesson-2"))
  private val unknownLesson = valid(LessonId("lesson-99"))
  private val defaultLimit  = valid(PageLimit(20))
  private val zeroOffset    = valid(PageOffset(0))
  private val baseTime      = Instant.parse("2026-09-07T12:00:00Z")

  test("schema extraction includes only Playback tables and indexes, not later catalog seeds") {
    playbackSchema.map { statements =>
      expect.all(
        statements.count(_.startsWith("CREATE TABLE ")) == 4,
        statements.count(_.startsWith("CREATE INDEX ")) == 2
      )
    }
  }

  test("projection lookups decode lessons and distinguish missing users, courses, and lessons") {
    withRepository { repository =>
      for
        knownUser           <- repository.userExists(alice)
        missingUser         <- repository.userExists(unknownUser)
        knownCourse         <- repository.courseExists(courseA)
        missingCourse       <- repository.courseExists(unknownCourse)
        lesson              <- repository.findLesson(courseA, lessonOne)
        missingLesson       <- repository.findLesson(courseA, unknownLesson)
        missingCourseLesson <- repository.findLesson(unknownCourse, lessonOne)
      yield expect.all(
        knownUser,
        !missingUser,
        knownCourse,
        !missingCourse,
        lesson.contains(
          Lesson(
            courseA,
            lessonOne,
            valid(LessonTitle("Test lesson")),
            valid(DurationSeconds(300)),
            true,
            valid(ObjectKey(s"courses/${courseA.value}/lesson-1.mp4"))
          )
        ),
        missingLesson.isEmpty,
        missingCourseLesson.isEmpty
      )
    }
  }

  test("progress upserts replace values while isolating users and lessons") {
    withRepository { repository =>
      val initial     = progress(courseA, lessonOne, 10, false, 0)
      val otherLesson = progress(courseA, lessonTwo, 20, false, 1)
      val otherUser   = progress(courseA, lessonOne, 30, false, 2)
      val completed   = progress(courseA, lessonOne, 300, true, 3)
      val restarted   = progress(courseA, lessonOne, 5, false, 4)

      for
        created            <- repository.saveProgress(alice, initial)
        _                  <- repository.saveProgress(alice, otherLesson)
        _                  <- repository.saveProgress(bob, otherUser)
        updated            <- repository.saveProgress(alice, completed)
        replaced           <- repository.saveProgress(alice, restarted)
        current            <- repository.findProgress(alice, courseA, lessonOne)
        otherLessonCurrent <- repository.findProgress(alice, courseA, lessonTwo)
        otherUserCurrent   <- repository.findProgress(bob, courseA, lessonOne)
        missing            <- repository.findProgress(alice, courseB, lessonOne)
        missingUser        <- repository.findProgress(unknownUser, courseA, lessonOne)
      yield expect.all(
        created == initial,
        updated == completed,
        replaced == restarted,
        current.contains(restarted),
        otherLessonCurrent.contains(otherLesson),
        otherUserCurrent.contains(otherUser),
        missing.isEmpty,
        missingUser.isEmpty
      )
    }
  }

  test("progress filters, stable ordering, pagination, and totals are scoped to the user") {
    withRepository { repository =>
      val first  = progress(courseB, lessonOne, 300, true, 2)
      val second = progress(courseA, lessonOne, 10, false, 1)
      val third  = progress(courseA, lessonTwo, 300, true, 1)
      val limit  = valid(PageLimit(1))
      val offset = valid(PageOffset(1))
      val beyond = valid(PageOffset(99))

      for
        _          <- List(third, first, second).traverse_(repository.saveProgress(alice, _))
        _          <- repository.saveProgress(bob, progress(courseB, lessonOne, 42, false, 10))
        all        <- repository.listProgress(alice, defaultLimit, zeroOffset, None, None)
        course     <- repository.listProgress(alice, defaultLimit, zeroOffset, Some(courseA), None)
        completed  <- repository.listProgress(alice, defaultLimit, zeroOffset, None, Some(true))
        incomplete <- repository.listProgress(alice, defaultLimit, zeroOffset, None, Some(false))
        combined   <-
          repository.listProgress(alice, defaultLimit, zeroOffset, Some(courseA), Some(true))
        page      <- repository.listProgress(alice, limit, offset, None, None)
        emptyPage <- repository.listProgress(alice, limit, beyond, None, None)
        emptyUser <- repository.listProgress(unknownUser, defaultLimit, zeroOffset, None, None)
      yield expect.all(
        all.items == List(first, second, third),
        all.total.value == 3L,
        course.items == List(second, third),
        course.total.value == 2L,
        completed.items == List(first, third),
        completed.total.value == 2L,
        incomplete.items == List(second),
        incomplete.total.value == 1L,
        combined.items == List(third),
        combined.total.value == 1L,
        page.items == List(second),
        page.total.value == 3L,
        page.limit == limit,
        page.offset == offset,
        emptyPage.items.isEmpty,
        emptyPage.total.value == 3L,
        emptyPage.limit == limit,
        emptyPage.offset == beyond,
        emptyUser.items.isEmpty,
        emptyUser.total.value == 0L
      )
    }
  }

  test("favorite adds preserve creation times and paginate without leaking another user's data") {
    withRepository { repository =>
      val first  = Favorite(courseA, at(0))
      val second = Favorite(courseB, at(1))
      val limit  = valid(PageLimit(1))
      val offset = valid(PageOffset(1))
      val beyond = valid(PageOffset(99))

      for
        created   <- repository.addFavorite(alice, courseA, first.createdAt)
        repeated  <- repository.addFavorite(alice, courseA, at(10))
        _         <- repository.addFavorite(alice, courseB, second.createdAt)
        _         <- repository.addFavorite(bob, courseA, at(20))
        missing   <- repository.addFavorite(alice, unknownCourse, at(30))
        all       <- repository.listFavorites(alice, defaultLimit, zeroOffset)
        page      <- repository.listFavorites(alice, limit, offset)
        emptyPage <- repository.listFavorites(alice, limit, beyond)
        emptyUser <- repository.listFavorites(unknownUser, defaultLimit, zeroOffset)
      yield expect.all(
        created.contains(first),
        repeated.contains(first),
        missing.isEmpty,
        all.items == List(second, first),
        all.total.value == 2L,
        page.items == List(first),
        page.total.value == 2L,
        page.limit == limit,
        page.offset == offset,
        emptyPage.items.isEmpty,
        emptyPage.total.value == 2L,
        emptyPage.limit == limit,
        emptyPage.offset == beyond,
        emptyUser.items.isEmpty,
        emptyUser.total.value == 0L
      )
    }
  }

  test(
    "favorite ordering breaks timestamp ties by course ID and deletion is scoped and idempotent"
  ) {
    withRepository { repository =>
      for
        _              <- repository.addFavorite(alice, courseB, at(0))
        _              <- repository.addFavorite(alice, courseA, at(0))
        _              <- repository.addFavorite(bob, courseA, at(1))
        ordered        <- repository.listFavorites(alice, defaultLimit, zeroOffset)
        _              <- repository.removeFavorite(alice, courseA)
        _              <- repository.removeFavorite(alice, courseA)
        _              <- repository.removeFavorite(alice, unknownCourse)
        aliceFavorites <- repository.listFavorites(alice, defaultLimit, zeroOffset)
        bobFavorites   <- repository.listFavorites(bob, defaultLimit, zeroOffset)
      yield expect.all(
        ordered.items.map(_.courseId) == List(courseA, courseB),
        aliceFavorites.items == List(Favorite(courseB, at(0))),
        aliceFavorites.total.value == 1L,
        bobFavorites.items == List(Favorite(courseA, at(1))),
        bobFavorites.total.value == 1L
      )
    }
  }

  test("concurrent favorite adds converge on one row and return the same original timestamp") {
    withRepository { repository =>
      val timestamps = (0L until 8L).toList.map(at)

      for
        results <- timestamps.parTraverse(repository.addFavorite(alice, courseA, _))
        page    <- repository.listFavorites(alice, defaultLimit, zeroOffset)
      yield expect.all(
        results.forall(_.nonEmpty),
        results.distinct.size == 1,
        results.flatten.forall(_.courseId == courseA),
        results.flatten.forall(favorite => timestamps.contains(favorite.createdAt)),
        page.total.value == 1L,
        page.items == results.head.toList
      )
    }
  }

  test(
    "foreign keys reject progress for unknown users or lessons and favorites for unknown users"
  ) {
    withRepository { repository =>
      for
        unknownProgressUser <-
          repository
            .saveProgress(unknownUser, progress(courseA, lessonOne, 1, false, 0))
            .attempt
        unknownProgressLesson <-
          repository
            .saveProgress(alice, progress(courseA, unknownLesson, 1, false, 0))
            .attempt
        unknownProgressCourse <-
          repository
            .saveProgress(alice, progress(unknownCourse, lessonOne, 1, false, 0))
            .attempt
        unknownFavoriteUser <- repository.addFavorite(unknownUser, courseA, at(0)).attempt
        progressPage        <- repository.listProgress(alice, defaultLimit, zeroOffset, None, None)
        favoritePage        <- repository.listFavorites(alice, defaultLimit, zeroOffset)
      yield expect.all(
        isForeignKeyViolation(unknownProgressUser),
        isForeignKeyViolation(unknownProgressLesson),
        isForeignKeyViolation(unknownProgressCourse),
        isForeignKeyViolation(unknownFavoriteUser),
        progressPage.total.value == 0L,
        favoritePage.total.value == 0L
      )
    }
  }

  test("the service composes projections, progress, and favorites with the real repository") {
    withRepository { repository =>
      val playback = PlaybackUrlResponse(
        valid(PlaybackUrl("https://videos.example.test/lesson.mp4?signature=test-only")),
        valid(ExpiresInSeconds(300))
      )
      val storage = new S3VideoStorage:
        override def getPlaybackUrl(objectKey: ObjectKey): IO[PlaybackUrlResponse] =
          IO.raiseUnless(objectKey.value == s"courses/${courseA.value}/lesson-1.mp4")(
            new AssertionError("Unexpected projected object key")
          ).as(playback)

      for
        context <- IOLocalRequestContext.create[UUID]
        service  = new PlaybackServiceImpl(repository, storage, context)
        result  <- context.scope(alice) {
                    for
                      url   <- service.getPlaybackUrl(courseA, lessonOne)
                      saved <- service.updatePlaybackProgress(
                                 courseA,
                                 lessonOne,
                                 valid(PositionSeconds(300))
                               )
                      progress <- service.listPlaybackProgress(
                                    defaultLimit,
                                    zeroOffset,
                                    Some(courseA),
                                    Some(true)
                                  )
                      favorite     <- service.addFavorite(courseA)
                      repeated     <- service.addFavorite(courseA)
                      favorites    <- service.listFavorites(defaultLimit, zeroOffset)
                      bobFavorites <-
                        context.scope(bob)(service.listFavorites(defaultLimit, zeroOffset))
                      _         <- service.removeFavorite(courseA)
                      _         <- service.removeFavorite(courseA)
                      remaining <- service.listFavorites(defaultLimit, zeroOffset)
                    yield expect.all(
                      url == playback,
                      saved.completed,
                      saved.positionSeconds.value == 300,
                      progress.items == List(saved),
                      progress.total.value == 1L,
                      repeated == favorite,
                      favorites.items == List(favorite),
                      bobFavorites.items.isEmpty,
                      remaining.items.isEmpty
                    )
                  }
        after <- context.get
      yield result and expect(after.isEmpty)
    }
  }

  private def withRepository(run: PlaybackRepository => IO[Expectations]): IO[Expectations] =
    if sys.env.get("PLAYBACK_REPOSITORY_TESTS").contains("true") then isolatedRepository.use(run)
    else
      ignore[IO](
        "Postgres integration test: enable with PLAYBACK_REPOSITORY_TESTS=true " +
          "sbt 'playbackService/testOnly *PlaybackRepositorySuite'"
      )

  private def isolatedRepository: Resource[IO, PlaybackRepository] =
    for
      statements <- Resource.eval(playbackSchema)
      schema     <- Resource.make(
                  IO(UUID.randomUUID())
                    .map(id => s"playback_repository_test_${id.toString.replace("-", "")}")
                    .flatTap(name => adminCommand(s"CREATE SCHEMA ${schemaIdentifier(name)}"))
                )(name => adminCommand(s"DROP SCHEMA ${schemaIdentifier(name)} CASCADE"))
      sessions <-
        connection
          .withConnectionParameters(Session.DefaultConnectionParameters + ("search_path" -> schema))
          .pooled(4)
      _ <- Resource.eval(sessions.use(session => statements.traverse_(execute(session, _))))
      _ <- Resource.eval(seed(new PlaybackProjectionRepositoryImpl(sessions)))
    yield new PlaybackRepositoryImpl(sessions)

  private def connection: Session.Builder[IO] =
    Session
      .Builder[IO]
      .withHost(sys.env.getOrElse("PLAYBACK_TEST_POSTGRES_HOST", "localhost"))
      .withPort(sys.env.getOrElse("PLAYBACK_TEST_POSTGRES_PORT", "5432").toInt)
      .withUserAndPassword(
        sys.env.getOrElse("PLAYBACK_TEST_POSTGRES_USER", "postgres"),
        sys.env.getOrElse("PLAYBACK_TEST_POSTGRES_PASSWORD", "postgres")
      )
      .withDatabase(sys.env.getOrElse("PLAYBACK_TEST_POSTGRES_DATABASE", "postgres"))

  private def schemaIdentifier(name: String): String =
    require(name.matches("playback_repository_test_[a-f0-9]{32}"), "Invalid isolated test schema")
    s"\"$name\""

  private def adminCommand(statement: String): IO[Unit] =
    connection.single.use(execute(_, statement))

  private def execute(session: Session[IO], statement: String): IO[Unit] =
    session.execute(Command(statement, Origin.unknown, Void.codec)).void

  private def playbackSchema: IO[List[String]] = IO.blocking {
    val relative = Path.of("infrastructure", "postgres", "init", "01-schema.sql")
    val source   = Iterator
      .iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve(relative))
      .find(path => Files.isRegularFile(path))
      .getOrElse(
        throw new IllegalStateException("Cannot locate the repository's Postgres init SQL")
      )
    val sql     = Files.readString(source)
    val section = sql.indexOf("-- Playback projections")
    require(section >= 0, "Playback schema section is missing")
    val marker = "SET ROLE playback;"
    val start  = sql.indexOf(marker, section)
    require(start >= 0, "Playback schema start is missing")
    val end = sql.indexOf("RESET ROLE;", start)
    require(end > start, "Playback schema end is missing")
    val ddl = sql
      .substring(start + marker.length, end)
      .linesIterator
      .filterNot(_.trim.startsWith("--"))
      .mkString("\n")
      .split(";")
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList
    require(ddl.nonEmpty, "Playback schema is empty")
    require(
      ddl.forall(statement =>
        statement.startsWith("CREATE TABLE ") || statement.startsWith("CREATE INDEX ")
      ),
      "Expected only Playback table/index DDL; refusing to execute other init commands"
    )
    ddl
  }

  private def seed(repository: PlaybackProjectionRepositoryImpl): IO[Unit] =
    val lessons = List((courseA, lessonOne), (courseA, lessonTwo), (courseB, lessonOne))

    List(alice, bob).traverse_ { id =>
      IO(UUID.randomUUID()).flatMap(eventId =>
        repository.userCreated(event.UserCreated(event.EventId(eventId), at(0), event.UserId(id)))
      )
    } *> lessons.traverse_ { case (course, lesson) =>
      IO(UUID.randomUUID()).flatMap(eventId =>
        repository.lessonPublished(
          event.LessonPublished(
            event.EventId(eventId),
            at(0),
            event.CourseId(course.value),
            valid(event.LessonId(lesson.value)),
            valid(event.LessonTitle("Test lesson")),
            valid(event.DurationSeconds(300)),
            true,
            valid(event.ObjectKey(s"courses/${course.value}/${lesson.value}.mp4"))
          )
        )
      )
    }

  private def progress(
      course: CourseId,
      lesson: LessonId,
      position: Int,
      completed: Boolean,
      seconds: Long
  ): PlaybackProgress =
    PlaybackProgress(course, lesson, valid(PositionSeconds(position)), completed, at(seconds))

  private def at(seconds: Long): Timestamp = Timestamp.fromInstant(baseTime.plusSeconds(seconds))

  private def valid[A](value: Either[String, A]): A =
    value.fold(message => throw new IllegalArgumentException(message), identity)

  private def isForeignKeyViolation[A](result: Either[Throwable, A]): Boolean =
    result match
      case Left(SqlState.ForeignKeyViolation(_)) => true
      case _ => false
