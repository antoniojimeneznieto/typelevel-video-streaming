package org.typelevel.video.streaming.backend.playback.repository

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import org.typelevel.video.streaming.backend.events.{LessonPublished, UserCreated}
import skunk.codec.all.*
import skunk.implicits.*
import skunk.{Command, Encoder, Session}
import PlaybackProjectionRepositoryImpl.*

trait PlaybackProjectionRepository:

  def userCreated(event: UserCreated): IO[Unit]

  def lessonPublished(event: LessonPublished): IO[Unit]

final class PlaybackProjectionRepositoryImpl(
    sessions: Resource[IO, Session[IO]]
) extends PlaybackProjectionRepository:

  override def userCreated(event: UserCreated): IO[Unit] =
    sessions.use(_.execute(insertUser)(event).void)

  override def lessonPublished(event: LessonPublished): IO[Unit] =
    sessions.use(_.execute(insertLesson)(event).void)

object PlaybackProjectionRepositoryImpl:

  private val userCreatedValues: Encoder[UserCreated] =
    (uuid *: uuid *: timestamptz).contramap { event =>
      (event.userId.value, event.eventId.value, event.occurredAt.toOffsetDateTime)
    }

  private val lessonPublishedValues: Encoder[LessonPublished] =
    (uuid *: text *: text *: int4 *: bool *: text *: uuid *: timestamptz).contramap { event =>
      (
        event.courseId.value,
        event.lessonId.value,
        event.title.value,
        event.durationSeconds.value,
        event.isPreview,
        event.objectKey.value,
        event.eventId.value,
        event.occurredAt.toOffsetDateTime
      )
    }

  private val insertUser: Command[UserCreated] =
    sql"""
      INSERT INTO users (id, event_id, created_at)
      VALUES ($userCreatedValues)
      ON CONFLICT (id) DO NOTHING
    """.command

  private val insertLesson: Command[LessonPublished] =
    sql"""
      INSERT INTO lessons (
        course_id,
        lesson_id,
        title,
        duration_seconds,
        is_preview,
        object_key,
        event_id,
        published_at
      )
      VALUES ($lessonPublishedValues)
      ON CONFLICT (course_id, lesson_id) DO NOTHING
    """.command
