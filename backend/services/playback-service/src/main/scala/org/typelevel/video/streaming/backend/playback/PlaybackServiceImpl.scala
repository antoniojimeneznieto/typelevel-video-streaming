package org.typelevel.video.streaming.backend.playback.service

import java.util.UUID

import cats.effect.{Clock, IO}
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.video.streaming.backend.playback.api.*
import org.typelevel.video.streaming.backend.playback.domain.*
import org.typelevel.video.streaming.backend.playback.repository.PlaybackRepository
import org.typelevel.video.streaming.backend.playback.storage.S3VideoStorage
import org.typelevel.video.streaming.backend.runtime.context.RequestContext
import smithy4s.time.Timestamp

final class PlaybackServiceImpl(
    repository: PlaybackRepository,
    videoStorage: S3VideoStorage,
    requestContext: RequestContext[IO, UUID],
)(using Slf4jFactory[IO])
    extends PlaybackService[IO]:

  private val logger = Slf4jFactory[IO].getLogger

  override def getPlaybackUrl(courseId: CourseId, lessonId: LessonId): IO[PlaybackUrlResponse] =
    for
      _      <- currentUser
      lesson <- findLesson(courseId, lessonId)
      url    <- videoStorage.getPlaybackUrl(lesson.objectKey)
    yield url

  override def updatePlaybackProgress(
      courseId: CourseId,
      lessonId: LessonId,
      positionSeconds: PositionSeconds,
  ): IO[PlaybackProgress] =
    for
      userId <- currentUser
      lesson <- findLesson(courseId, lessonId)
      _ <- logger.info(Map("course.id" -> courseId.toString, "lesson.id" -> lessonId.toString))(
             s"Updating playback progress to $positionSeconds",
           )
      _ <- IO.raiseWhen(positionSeconds.value > lesson.durationSeconds.value)(
             InvalidPlaybackProgressError("Position must not exceed the video duration"),
           )
      now     <- Clock[IO].realTimeInstant.map(Timestamp.fromInstant)
      progress = PlaybackProgress(
                   courseId        = courseId,
                   lessonId        = lessonId,
                   positionSeconds = positionSeconds,
                   completed       = positionSeconds.value == lesson.durationSeconds.value,
                   updatedAt       = now,
                 )
      saved <- repository.saveProgress(userId, progress)
    yield saved

  override def listPlaybackProgress(
      limit: PageLimit,
      offset: PageOffset,
      courseId: Option[CourseId],
      completed: Option[Boolean],
  ): IO[PlaybackProgressPage] =
    currentUser.flatMap { userId =>
      repository.listProgress(userId, limit, offset, courseId, completed)
    }

  override def addFavorite(courseId: CourseId): IO[Favorite] =
    for
      userId   <- currentUser
      now      <- Clock[IO].realTimeInstant.map(Timestamp.fromInstant)
      favorite <- repository.addFavorite(userId, courseId, now).flatMap {
                    case Some(favorite) => IO.pure(favorite)
                    case None => IO.raiseError(CourseNotFoundError("Course not found"))
                  }
    yield favorite

  override def removeFavorite(courseId: CourseId): IO[Unit] =
    currentUser.flatMap { userId =>
      repository.removeFavorite(userId, courseId)
    }

  override def listFavorites(limit: PageLimit, offset: PageOffset): IO[FavoritePage] =
    currentUser.flatMap { userId =>
      repository.listFavorites(userId, limit, offset)
    }

  private def currentUser: IO[UUID] =
    requestContext.get.flatMap {
      case Some(userId) =>
        repository.userExists(userId).flatMap {
          case true => IO.pure(userId)
          case false => IO.raiseError(PlaybackUnavailableError("User data is not available yet"))
        }
      case None =>
        IO.raiseError(new IllegalStateException("Authenticated request context is missing"))
    }

  private def findLesson(courseId: CourseId, lessonId: LessonId): IO[Lesson] =
    repository.findLesson(courseId, lessonId).flatMap {
      case Some(lesson) => IO.pure(lesson)
      case None => IO.raiseError(VideoNotFoundError("Video not found"))
    }
