package org.typelevel.video.streaming.backend.playback

import java.time.Instant
import java.util.UUID

import cats.effect.{Clock, IO, Ref}
import cats.syntax.all.*
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.video.streaming.backend.playback.api.*
import org.typelevel.video.streaming.backend.playback.domain.*
import org.typelevel.video.streaming.backend.playback.repository.PlaybackRepository
import org.typelevel.video.streaming.backend.playback.service.PlaybackServiceImpl
import org.typelevel.video.streaming.backend.playback.storage.S3VideoStorage
import org.typelevel.video.streaming.backend.runtime.context.IOLocalRequestContext
import smithy4s.time.Timestamp
import weaver.SimpleIOSuite

object PlaybackServiceImplSuite extends SimpleIOSuite:

  private given Slf4jFactory[IO] = Slf4jFactory.create[IO]

  private val alice           = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val bob             = UUID.fromString("00000000-0000-0000-0000-000000000002")
  private val courseId        = CourseId(UUID.fromString("00000000-0000-0000-0000-000000000104"))
  private val lessonId        = valid(LessonId("lesson-1"))
  private val limit           = valid(PageLimit(4))
  private val offset          = valid(PageOffset(7))
  private val position        = valid(PositionSeconds(120))
  private val publishedLesson = Lesson(
    courseId,
    lessonId,
    valid(LessonTitle("Threads at Scale")),
    valid(DurationSeconds(300)),
    true,
    valid(ObjectKey("published/talks/threads-at-scale.mp4")),
  )
  private val originalFavorite = Favorite(courseId, Timestamp.fromInstant(Instant.EPOCH))
  private val progressPage     = PlaybackProgressPage(Nil, valid(TotalCount(12)), limit, offset)
  private val favoritePage     =
    FavoritePage(List(originalFavorite), valid(TotalCount(8)), limit, offset)
  private val playback = PlaybackUrlResponse(
    valid(PlaybackUrl("https://videos.example.test/signed.mp4?signature=test-only")),
    valid(ExpiresInSeconds(900)),
  )
  private val privateFailure = new RuntimeException(
    "database password=private-detail object=private-key",
  )

  test("all operations require an authenticated context before accessing data or storage") {
    for
      fixture <- setup()
      results <- operations(fixture.service).traverse(_.attempt)
      calls   <- fixture.calls.get
      storage <- fixture.storageCalls.get
    yield expect.all(
      results.forall(_.left.exists(_.isInstanceOf[IllegalStateException])),
      calls.isEmpty,
      storage.isEmpty,
    )
  }

  test("all operations reject a missing user projection without accessing other data or storage") {
    for
      fixture <- setup(Settings(userPresent = false))
      results <- fixture.context.scope(alice)(operations(fixture.service).traverse(_.attempt))
      calls   <- fixture.calls.get
      storage <- fixture.storageCalls.get
      after   <- fixture.context.get
    yield expect.all(
      results.forall(_ == Left(PlaybackUnavailableError("User data is not available yet"))),
      calls == Vector.fill(6)(Call.UserExists(alice)),
      storage.isEmpty,
      after.isEmpty,
    )
  }

  test("playback signs the exact key from the local lesson projection") {
    for
      fixture  <- setup()
      response <- fixture.context.scope(alice)(fixture.service.getPlaybackUrl(courseId, lessonId))
      calls    <- fixture.calls.get
      storage  <- fixture.storageCalls.get
      after    <- fixture.context.get
    yield expect.all(
      response == playback,
      calls == Vector(Call.UserExists(alice), Call.FindLesson(courseId, lessonId)),
      storage == Vector(publishedLesson.objectKey),
      after.isEmpty,
    )
  }

  test("missing lessons return modeled 404s without signing or saving progress") {
    for
      fixture <- setup(Settings(lesson = None))
      results <- fixture.context.scope(alice) {
                   List(
                     fixture.service.getPlaybackUrl(courseId, lessonId).void,
                     fixture.service.updatePlaybackProgress(courseId, lessonId, position).void,
                   ).traverse(_.attempt)
                 }
      calls   <- fixture.calls.get
      storage <- fixture.storageCalls.get
    yield expect.all(
      results.forall(_ == Left(VideoNotFoundError("Video not found"))),
      calls == Vector(
        Call.UserExists(alice),
        Call.FindLesson(courseId, lessonId),
        Call.UserExists(alice),
        Call.FindLesson(courseId, lessonId),
      ),
      storage.isEmpty,
    )
  }

  test("progress computes completion, timestamps each write, and forwards the current user's ID") {
    for
      fixture <- setup()
      before  <- Clock[IO].realTimeInstant
      initial <-
        fixture.context.scope(bob)(
          fixture.service.updatePlaybackProgress(courseId, lessonId, valid(PositionSeconds(0))),
        )
      completed <-
        fixture.context.scope(alice)(
          fixture.service.updatePlaybackProgress(courseId, lessonId, valid(PositionSeconds(300))),
        )
      rewound <- fixture.context.scope(alice)(
                   fixture.service.updatePlaybackProgress(courseId, lessonId, position),
                 )
      after <- Clock[IO].realTimeInstant
      calls <- fixture.calls.get
      writes = calls.collect { case Call.SaveProgress(user, progress) => user -> progress }
    yield expect.all(
      initial.positionSeconds.value == 0,
      !initial.completed,
      completed.positionSeconds.value == 300,
      completed.completed,
      rewound.positionSeconds == position,
      !rewound.completed,
      writes == Vector(bob -> initial, alice -> completed, alice -> rewound),
      writes.forall { case (_, progress) =>
        progress.courseId == courseId && progress.lessonId == lessonId &&
        between(progress.updatedAt, before, after)
      },
    )
  }

  test("positions beyond the lesson duration are rejected before persistence") {
    for
      fixture <- setup()
      result  <- fixture.context.scope(alice)(
                  fixture.service
                    .updatePlaybackProgress(courseId, lessonId, valid(PositionSeconds(301)))
                    .attempt,
                )
      calls <- fixture.calls.get
    yield expect.all(
      result == Left(InvalidPlaybackProgressError("Position must not exceed the video duration")),
      calls == Vector(Call.UserExists(alice), Call.FindLesson(courseId, lessonId)),
    )
  }

  test("lists forward pagination, optional filters, and the scoped user unchanged") {
    for
      fixture  <- setup()
      filtered <- fixture.context.scope(alice)(
                    fixture.service.listPlaybackProgress(limit, offset, Some(courseId), Some(false)),
                  )
      unfiltered <- fixture.context.scope(bob)(
                      fixture.service.listPlaybackProgress(limit, offset, None, None),
                    )
      favorites <- fixture.context.scope(bob)(fixture.service.listFavorites(limit, offset))
      calls     <- fixture.calls.get
    yield expect.all(
      filtered == progressPage,
      unfiltered == progressPage,
      favorites == favoritePage,
      calls == Vector(
        Call.UserExists(alice),
        Call.ListProgress(alice, limit, offset, Some(courseId), Some(false)),
        Call.UserExists(bob),
        Call.ListProgress(bob, limit, offset, None, None),
        Call.UserExists(bob),
        Call.ListFavorites(bob, limit, offset),
      ),
    )
  }

  test(
    "favorite writes are user-scoped and preserve the repository's original creation timestamp",
  ) {
    for
      fixture  <- setup()
      before   <- Clock[IO].realTimeInstant
      favorite <- fixture.context.scope(alice)(fixture.service.addFavorite(courseId))
      after    <- Clock[IO].realTimeInstant
      _        <- fixture.context.scope(alice)(fixture.service.removeFavorite(courseId))
      _        <- fixture.context.scope(alice)(fixture.service.removeFavorite(courseId))
      _        <- fixture.context.scope(bob)(fixture.service.removeFavorite(courseId))
      calls    <- fixture.calls.get
      adds      = calls.collect { case Call.AddFavorite(user, course, at) => (user, course, at) }
      removes   = calls.collect { case Call.RemoveFavorite(user, course) => (user, course) }
    yield expect.all(
      favorite == originalFavorite,
      adds.size == 1,
      adds.forall { case (user, course, at) =>
        user == alice && course == courseId && between(at, before, after)
      },
      removes == Vector((alice, courseId), (alice, courseId), (bob, courseId)),
    )
  }

  test("adding a favorite for a missing projected course returns a modeled 404") {
    for
      fixture <- setup(Settings(favorite = None))
      result  <- fixture.context.scope(alice)(fixture.service.addFavorite(courseId).attempt)
      calls   <- fixture.calls.get
    yield expect.all(
      result == Left(CourseNotFoundError("Course not found")),
      calls.collect { case Call.AddFavorite(user, course, _) => (user, course) } ==
        Vector((alice, courseId)),
    )
  }

  test(
    "repository failures propagate unchanged at user checks and every operation's data boundary",
  ) {
    List(Settings(failUserCheck = true), Settings(failRepository = true))
      .traverse { settings =>
        for
          fixture <- setup(settings)
          results <- fixture.context.scope(alice)(operations(fixture.service).traverse(_.attempt))
          storage <- fixture.storageCalls.get
        yield expect.all(
          results.forall(_.left.exists(_ eq privateFailure)),
          storage.isEmpty,
        )
      }
      .map(_.reduce(_ and _))
  }

  test("progress persistence failures propagate unchanged after successful lesson validation") {
    for
      fixture <- setup(Settings(failSave = true))
      result  <- fixture.context.scope(alice)(
                  fixture.service.updatePlaybackProgress(courseId, lessonId, position).attempt,
                )
      calls <- fixture.calls.get
    yield expect.all(
      result.left.exists(_ eq privateFailure),
      calls.collect { case Call.SaveProgress(user, _) => user } == Vector(alice),
    )
  }

  test("modeled storage 404 and 503 failures remain storage errors") {
    List(
      VideoNotFoundError("Video not found"),
      PlaybackUnavailableError("Video storage is temporarily unavailable"),
    ).traverse { failure =>
      for
        fixture <- setup(storageResult = IO.raiseError(failure))
        result  <-
          fixture.context.scope(alice)(fixture.service.getPlaybackUrl(courseId, lessonId).attempt)
        storage <- fixture.storageCalls.get
      yield expect.all(result == Left(failure), storage == Vector(publishedLesson.objectKey))
    }.map(_.reduce(_ and _))
  }

  private enum Call:
    case UserExists(user: UUID)
    case FindLesson(course: CourseId, lesson: LessonId)
    case SaveProgress(user: UUID, progress: PlaybackProgress)
    case ListProgress(
        user: UUID,
        limit: PageLimit,
        offset: PageOffset,
        course: Option[CourseId],
        completed: Option[Boolean],
    )
    case AddFavorite(user: UUID, course: CourseId, createdAt: Timestamp)
    case RemoveFavorite(user: UUID, course: CourseId)
    case ListFavorites(user: UUID, limit: PageLimit, offset: PageOffset)

  final private case class Settings(
      userPresent: Boolean       = true,
      lesson: Option[Lesson]     = Some(publishedLesson),
      favorite: Option[Favorite] = Some(originalFavorite),
      failUserCheck: Boolean     = false,
      failRepository: Boolean    = false,
      failSave: Boolean          = false,
  )

  final private case class Fixture(
      service: PlaybackServiceImpl,
      context: IOLocalRequestContext[UUID],
      calls: Ref[IO, Vector[Call]],
      storageCalls: Ref[IO, Vector[ObjectKey]],
  )

  private def setup(
      settings: Settings                     = Settings(),
      storageResult: IO[PlaybackUrlResponse] = IO.pure(playback),
  ): IO[Fixture] =
    for
      context      <- IOLocalRequestContext.create[UUID]
      calls        <- Ref.of[IO, Vector[Call]](Vector.empty)
      storageCalls <- Ref.of[IO, Vector[ObjectKey]](Vector.empty)
      repository    = new RecordingRepository(settings, calls)
      storage       = new S3VideoStorage:
                  override def getPlaybackUrl(objectKey: ObjectKey): IO[PlaybackUrlResponse] =
                    storageCalls.update(_ :+ objectKey) *> storageResult
      service = new PlaybackServiceImpl(repository, storage, context)
    yield Fixture(service, context, calls, storageCalls)

  final private class RecordingRepository(settings: Settings, calls: Ref[IO, Vector[Call]])
      extends PlaybackRepositoryStub:

    private def record[A](call: Call, fails: Boolean)(result: A): IO[A] =
      calls.update(_ :+ call) *> (if fails then IO.raiseError(privateFailure) else IO.pure(result))

    override def userExists(userId: UUID): IO[Boolean] =
      record(Call.UserExists(userId), settings.failUserCheck)(settings.userPresent)

    override def findLesson(courseId: CourseId, lessonId: LessonId): IO[Option[Lesson]] =
      record(Call.FindLesson(courseId, lessonId), settings.failRepository)(settings.lesson)

    override def saveProgress(userId: UUID, progress: PlaybackProgress): IO[PlaybackProgress] =
      record(Call.SaveProgress(userId, progress), settings.failRepository || settings.failSave)(
        progress,
      )

    override def listProgress(
        userId: UUID,
        limit: PageLimit,
        offset: PageOffset,
        courseId: Option[CourseId],
        completed: Option[Boolean],
    ): IO[PlaybackProgressPage] =
      record(
        Call.ListProgress(userId, limit, offset, courseId, completed),
        settings.failRepository,
      )(progressPage)

    override def addFavorite(
        userId: UUID,
        courseId: CourseId,
        createdAt: Timestamp,
    ): IO[Option[Favorite]] =
      record(Call.AddFavorite(userId, courseId, createdAt), settings.failRepository)(
        settings.favorite,
      )

    override def removeFavorite(userId: UUID, courseId: CourseId): IO[Unit] =
      record(Call.RemoveFavorite(userId, courseId), settings.failRepository)(())

    override def listFavorites(
        userId: UUID,
        limit: PageLimit,
        offset: PageOffset,
    ): IO[FavoritePage] =
      record(Call.ListFavorites(userId, limit, offset), settings.failRepository)(favoritePage)

  private def operations(service: PlaybackServiceImpl): List[IO[Unit]] = List(
    service.getPlaybackUrl(courseId, lessonId).void,
    service.updatePlaybackProgress(courseId, lessonId, position).void,
    service.listPlaybackProgress(limit, offset, Some(courseId), Some(true)).void,
    service.addFavorite(courseId).void,
    service.removeFavorite(courseId),
    service.listFavorites(limit, offset).void,
  )

  private def between(value: Timestamp, before: Instant, after: Instant): Boolean =
    !value.toInstant.isBefore(before) && !value.toInstant.isAfter(after)

  private def valid[A](value: Either[String, A]): A =
    value.fold(message => throw new AssertionError(message), identity)

private[playback] class PlaybackRepositoryStub extends PlaybackRepository:

  protected def unexpected[A]: IO[A] =
    IO.raiseError(new AssertionError("Unexpected repository call"))

  override def userExists(userId: UUID): IO[Boolean]                                  = unexpected
  override def courseExists(courseId: CourseId): IO[Boolean]                          = unexpected
  override def findLesson(courseId: CourseId, lessonId: LessonId): IO[Option[Lesson]] = unexpected
  override def saveProgress(userId: UUID, progress: PlaybackProgress): IO[PlaybackProgress] =
    unexpected
  override def findProgress(
      userId: UUID,
      courseId: CourseId,
      lessonId: LessonId,
  ): IO[Option[PlaybackProgress]] = unexpected
  override def listProgress(
      userId: UUID,
      limit: PageLimit,
      offset: PageOffset,
      courseId: Option[CourseId],
      completed: Option[Boolean],
  ): IO[PlaybackProgressPage] = unexpected
  override def addFavorite(
      userId: UUID,
      courseId: CourseId,
      createdAt: Timestamp,
  ): IO[Option[Favorite]]                                                 = unexpected
  override def removeFavorite(userId: UUID, courseId: CourseId): IO[Unit] = unexpected
  override def listFavorites(userId: UUID, limit: PageLimit, offset: PageOffset): IO[FavoritePage] =
    unexpected
