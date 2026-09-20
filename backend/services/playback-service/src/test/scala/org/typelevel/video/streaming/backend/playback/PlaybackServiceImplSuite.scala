package org.typelevel.video.streaming.backend.playback

import java.util.UUID

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.video.streaming.backend.playback.api.*
import org.typelevel.video.streaming.backend.playback.domain.*
import org.typelevel.video.streaming.backend.playback.repository.{
  PlaybackRepository,
  PlaybackRepositoryImpl,
}
import org.typelevel.video.streaming.backend.playback.service.PlaybackServiceImpl
import org.typelevel.video.streaming.backend.playback.storage.S3VideoStorage
import org.typelevel.video.streaming.backend.runtime.context.IOLocalRequestContext
import weaver.SimpleIOSuite

object PlaybackServiceImplSuite extends SimpleIOSuite with PlaybackFixture:

  ///////////////////////////////////////////////////////////////////////////////
  // preparation
  ///////////////////////////////////////////////////////////////////////////////

  private given Slf4jFactory[IO] = Slf4jFactory.create[IO]

  private val videoStorage = new S3VideoStorage:
    override def getPlaybackUrl(objectKey: ObjectKey): IO[PlaybackUrlResponse] =
      IO.raiseUnless(objectKey == lesson.objectKey)(
        new AssertionError("Expected the object key from the lesson projection"),
      ).as(playback)

  private def serviceWithDatabase: Resource[
    IO,
    (PlaybackService[IO], PlaybackRepository, IOLocalRequestContext[UUID]),
  ] =
    for
      sessions  <- sessionPool
      context   <- Resource.eval(IOLocalRequestContext.create[UUID])
      repository = new PlaybackRepositoryImpl(sessions)
      service    = new PlaybackServiceImpl(repository, videoStorage, context)
    yield (service, repository, context)

  ///////////////////////////////////////////////////////////////////////////////
  // tests
  ///////////////////////////////////////////////////////////////////////////////

  test("playback reads the lesson projection and signs its stored object key") {
    serviceWithDatabase.use { (service, repository, context) =>
      for
        stored   <- repository.findLesson(courseId, lessonId)
        response <- context.scope(alice)(service.getPlaybackUrl(courseId, lessonId))
        after    <- context.get
      yield expect.all(
        stored.contains(lesson),
        response == playback,
        after.isEmpty,
      )
    }
  }

  test("all operations require authentication and an existing user projection") {
    serviceWithDatabase.use { (service, _, context) =>
      val operations = List(
        service.getPlaybackUrl(courseId, lessonId).void,
        service.updatePlaybackProgress(courseId, lessonId, progress.positionSeconds).void,
        service.listPlaybackProgress().void,
        service.addFavorite(courseId).void,
        service.removeFavorite(courseId),
        service.listFavorites().void,
      )

      for
        unauthenticated <- operations.traverse(_.attempt)
        unavailable     <- context.scope(unknownUser)(operations.traverse(_.attempt))
        progress        <- context.scope(alice)(service.listPlaybackProgress())
        favorites       <- context.scope(alice)(service.listFavorites())
      yield expect.all(
        unauthenticated.forall(_.left.exists(_.isInstanceOf[IllegalStateException])),
        unavailable.forall(_ == Left(PlaybackUnavailableError("User data is not available yet"))),
        progress.items.isEmpty,
        favorites.items.isEmpty,
      )
    }
  }

  test("missing lessons and courses return errors without saving user data") {
    serviceWithDatabase.use { (service, _, context) =>
      context.scope(alice) {
        for
          url    <- service.getPlaybackUrl(courseId, missingLessonId).attempt
          update <- service
                      .updatePlaybackProgress(courseId, missingLessonId, progress.positionSeconds)
                      .attempt
          favorite  <- service.addFavorite(missingCourseId).attempt
          progress  <- service.listPlaybackProgress()
          favorites <- service.listFavorites()
        yield expect.all(
          url == Left(VideoNotFoundError("Video not found")),
          update == Left(VideoNotFoundError("Video not found")),
          favorite == Left(CourseNotFoundError("Course not found")),
          progress.total.value == 0L,
          favorites.total.value == 0L,
        )
      }
    }
  }

  test("progress updates compute completion and keep each user's lessons separate") {
    serviceWithDatabase.use { (service, repository, context) =>
      context.scope(alice) {
        for
          otherUser <-
            context.scope(bob)(
              service.updatePlaybackProgress(courseId, lessonId, valid(PositionSeconds(10))),
            )
          otherLesson <-
            service.updatePlaybackProgress(courseId, otherLessonId, valid(PositionSeconds(20)))
          initial   <- service.updatePlaybackProgress(courseId, lessonId, valid(PositionSeconds(0)))
          completed <-
            service.updatePlaybackProgress(courseId, lessonId, valid(PositionSeconds(300)))
          rewound <- service.updatePlaybackProgress(courseId, lessonId, progress.positionSeconds)
          stored  <- repository.findProgress(alice, courseId, lessonId)
          storedOtherLesson <- repository.findProgress(alice, courseId, otherLessonId)
          storedOtherUser   <- repository.findProgress(bob, courseId, lessonId)
          page              <- service.listPlaybackProgress()
        yield expect.all(
          initial.positionSeconds.value == 0,
          !initial.completed,
          completed.positionSeconds.value == 300,
          completed.completed,
          rewound.positionSeconds == progress.positionSeconds,
          !rewound.completed,
          stored.contains(rewound),
          storedOtherLesson.contains(otherLesson),
          storedOtherUser.contains(otherUser),
          page.total.value == 2L,
        )
      }
    }
  }

  test("a position beyond the lesson duration does not overwrite saved progress") {
    serviceWithDatabase.use { (service, repository, context) =>
      context.scope(alice) {
        for
          saved   <- service.updatePlaybackProgress(courseId, lessonId, progress.positionSeconds)
          invalid <-
            service.updatePlaybackProgress(courseId, lessonId, valid(PositionSeconds(301))).attempt
          stored <- repository.findProgress(alice, courseId, lessonId)
        yield expect.all(
          invalid == Left(
            InvalidPlaybackProgressError("Position must not exceed the video duration"),
          ),
          stored.contains(saved),
        )
      }
    }
  }

  test("progress filtering and pagination preserve totals, ordering, and user isolation") {
    serviceWithDatabase.use { (service, repository, context) =>
      context.scope(alice) {
        val limit       = valid(PageLimit(1))
        val offset      = valid(PageOffset(1))
        val beyond      = valid(PageOffset(99))
        val bobProgress = progress.copy(positionSeconds = valid(PositionSeconds(42)))

        for
          _          <- progressPage.items.reverse.traverse_(repository.saveProgress(alice, _))
          _          <- repository.saveProgress(bob, bobProgress)
          all        <- service.listPlaybackProgress()
          course     <- service.listPlaybackProgress(courseId = Some(courseId))
          completed  <- service.listPlaybackProgress(completed = Some(true))
          incomplete <- service.listPlaybackProgress(completed = Some(false))
          combined   <-
            service.listPlaybackProgress(courseId = Some(courseId), completed = Some(true))
          page    <- service.listPlaybackProgress(limit = limit, offset = offset)
          empty   <- service.listPlaybackProgress(limit = limit, offset = beyond)
          missing <- service.listPlaybackProgress(courseId = Some(missingCourseId))
          bobPage <- context.scope(bob)(service.listPlaybackProgress())
        yield expect.all(
          all == progressPage,
          course == progressPage
            .copy(items = List(progress, completedProgress), total = valid(TotalCount(2))),
          completed == progressPage.copy(
            items = List(otherCourseProgress, completedProgress),
            total = valid(TotalCount(2)),
          ),
          incomplete == progressPage.copy(items = List(progress), total = valid(TotalCount(1))),
          combined == progressPage
            .copy(items = List(completedProgress), total = valid(TotalCount(1))),
          page == progressPage.copy(items = List(progress), limit = limit, offset = offset),
          empty == progressPage.copy(items = Nil, limit = limit, offset = beyond),
          missing == progressPage.copy(items = Nil, total = valid(TotalCount(0))),
          bobPage == progressPage.copy(items = List(bobProgress), total = valid(TotalCount(1))),
        )
      }
    }
  }

  test("favorites are idempotent, paginated, and added and removed only for the current user") {
    serviceWithDatabase.use { (service, _, context) =>
      context.scope(alice) {
        val limit  = valid(PageLimit(1))
        val offset = valid(PageOffset(1))
        val beyond = valid(PageOffset(99))

        for
          first       <- service.addFavorite(courseId)
          repeated    <- service.addFavorite(courseId)
          second      <- service.addFavorite(otherCourseId)
          emptyUser   <- context.scope(bob)(service.listFavorites())
          bobFavorite <- context.scope(bob)(service.addFavorite(courseId))
          all         <- service.listFavorites()
          page        <- service.listFavorites(limit = limit, offset = offset)
          empty       <- service.listFavorites(limit = limit, offset = beyond)
          _           <- service.removeFavorite(courseId)
          _           <- service.removeFavorite(courseId)
          _           <- service.removeFavorite(missingCourseId)
          remaining   <- service.listFavorites()
          bobPage     <- context.scope(bob)(service.listFavorites())
        yield expect.all(
          repeated == first,
          all == favoritePage.copy(items = List(second, first)),
          page == favoritePage.copy(items = List(first), limit = limit, offset = offset),
          empty == favoritePage.copy(items = Nil, limit = limit, offset = beyond),
          emptyUser == favoritePage.copy(items = Nil, total = valid(TotalCount(0))),
          remaining == favoritePage.copy(items = List(second), total = valid(TotalCount(1))),
          bobPage == favoritePage.copy(items = List(bobFavorite), total = valid(TotalCount(1))),
        )
      }
    }
  }

  test("concurrent favorite additions create one row and return its original timestamp") {
    serviceWithDatabase.use { (service, _, context) =>
      context.scope(alice) {
        for
          favorites <- List.fill(8)(courseId).parTraverse(service.addFavorite)
          page      <- service.listFavorites()
        yield expect.all(
          favorites.distinct.size == 1,
          favorites.forall(_.courseId == courseId),
          page.items == favorites.take(1),
          page.total.value == 1L,
        )
      }
    }
  }

  test("storage failures propagate unchanged through the service") {
    serviceWithDatabase.use { (_, repository, context) =>
      List(
        VideoNotFoundError("Video not found"),
        PlaybackUnavailableError("Video storage is temporarily unavailable"),
      ).traverse { error =>
        val storage = new S3VideoStorage:
          override def getPlaybackUrl(objectKey: ObjectKey): IO[PlaybackUrlResponse] =
            IO.raiseError(error)
        val service = new PlaybackServiceImpl(repository, storage, context)

        context
          .scope(alice)(service.getPlaybackUrl(courseId, lessonId).attempt)
          .map(result => expect(result == Left(error)))
      }.map(_.reduce(_ and _))
    }
  }
