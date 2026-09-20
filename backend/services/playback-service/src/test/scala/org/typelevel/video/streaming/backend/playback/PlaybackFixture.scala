package org.typelevel.video.streaming.backend.playback

import java.time.Instant
import java.util.UUID

import cats.effect.IO
import org.http4s.{DecodeResult, EntityDecoder, MalformedMessageBodyFailure, MediaType}
import org.typelevel.video.streaming.backend.playback.api.*
import org.typelevel.video.streaming.backend.playback.domain.*
import org.typelevel.video.streaming.backend.runtime.postgres.SkunkSpec
import smithy4s.{Blob, Schema}
import smithy4s.json.Json
import smithy4s.time.Timestamp

trait PlaybackFixture extends SkunkSpec:

  ///////////////////////////////////////////////////////////////////////////////
  // test data
  ///////////////////////////////////////////////////////////////////////////////

  protected val alice           = new UUID(0L, 1L)
  protected val bob             = new UUID(0L, 2L)
  protected val unknownUser     = new UUID(0L, 99L)
  protected val courseId        = CourseId(UUID.fromString("00000000-0000-0000-0000-000000000104"))
  protected val otherCourseId   = CourseId(UUID.fromString("00000000-0000-0000-0000-000000000105"))
  protected val missingCourseId = CourseId(UUID.fromString("00000000-0000-0000-0000-000000000999"))
  protected val lessonId        = valid(LessonId("lesson-1"))
  protected val otherLessonId   = valid(LessonId("lesson-2"))
  protected val missingLessonId = valid(LessonId("missing"))
  protected val now             = Timestamp.fromInstant(Instant.parse("2026-09-07T12:00:00Z"))
  protected val later           = Timestamp.fromInstant(now.toInstant.plusSeconds(1))
  protected val accessToken     = "test-access-token"

  protected val lesson = Lesson(
    courseId,
    lessonId,
    valid(LessonTitle("Threads at Scale")),
    valid(DurationSeconds(300)),
    true,
    valid(ObjectKey("published/talks/threads-at-scale.mp4")),
  )

  protected val playback = PlaybackUrlResponse(
    valid(PlaybackUrl("https://videos.example.test/threads-at-scale.mp4?signature=test-only")),
    valid(ExpiresInSeconds(900)),
  )

  protected val progressInput = UpdatePlaybackProgressInput(
    courseId,
    lessonId,
    valid(PositionSeconds(120)),
  )

  protected val progress = PlaybackProgress(
    courseId,
    lessonId,
    progressInput.positionSeconds,
    false,
    now,
  )

  protected val completedProgress = progress.copy(
    lessonId        = otherLessonId,
    positionSeconds = valid(PositionSeconds(300)),
    completed       = true,
  )

  protected val otherCourseProgress = completedProgress.copy(
    courseId  = otherCourseId,
    lessonId  = lessonId,
    updatedAt = later,
  )

  protected val progressPage = PlaybackProgressPage(
    List(otherCourseProgress, progress, completedProgress),
    valid(TotalCount(3)),
    valid(PageLimit(20)),
    valid(PageOffset(0)),
  )

  protected val favorite      = Favorite(courseId, now)
  protected val otherFavorite = Favorite(otherCourseId, later)
  protected val favoritePage  = FavoritePage(
    List(otherFavorite, favorite),
    valid(TotalCount(2)),
    valid(PageLimit(20)),
    valid(PageOffset(0)),
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
  // database setup
  ///////////////////////////////////////////////////////////////////////////////

  override protected val initScript: String = "sql/playback.sql"

  ///////////////////////////////////////////////////////////////////////////////
  // validation helper
  ///////////////////////////////////////////////////////////////////////////////

  protected def valid[A](value: Either[String, A]): A =
    value.fold(message => throw new AssertionError(message), identity)
