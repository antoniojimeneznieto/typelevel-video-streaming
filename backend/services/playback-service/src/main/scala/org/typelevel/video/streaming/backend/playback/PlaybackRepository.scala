package org.typelevel.video.streaming.backend.playback.repository

import java.util.UUID

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import org.typelevel.video.streaming.backend.playback.domain.*
import skunk.codec.all.*
import skunk.implicits.*
import skunk.{Codec, Command, Query, Session}
import smithy4s.time.Timestamp
import PlaybackRepositoryImpl.*

trait PlaybackRepository:

  def userExists(userId: UUID): IO[Boolean]

  def courseExists(courseId: CourseId): IO[Boolean]

  def findLesson(courseId: CourseId, lessonId: LessonId): IO[Option[Lesson]]

  def saveProgress(userId: UUID, progress: PlaybackProgress): IO[PlaybackProgress]

  def findProgress(
      userId: UUID,
      courseId: CourseId,
      lessonId: LessonId
  ): IO[Option[PlaybackProgress]]

  def listProgress(
      userId: UUID,
      limit: PageLimit,
      offset: PageOffset,
      courseId: Option[CourseId],
      completed: Option[Boolean]
  ): IO[PlaybackProgressPage]

  def addFavorite(userId: UUID, courseId: CourseId, createdAt: Timestamp): IO[Option[Favorite]]

  def removeFavorite(userId: UUID, courseId: CourseId): IO[Unit]

  def listFavorites(userId: UUID, limit: PageLimit, offset: PageOffset): IO[FavoritePage]

final class PlaybackRepositoryImpl(
    sessions: Resource[IO, Session[IO]]
) extends PlaybackRepository:

  override def userExists(userId: UUID): IO[Boolean] =
    sessions.use(_.unique(selectUserExists)(userId))

  override def courseExists(courseId: CourseId): IO[Boolean] =
    sessions.use(_.unique(selectCourseExists)(courseId))

  override def findLesson(courseId: CourseId, lessonId: LessonId): IO[Option[Lesson]] =
    sessions.use(_.option(selectLesson)((courseId, lessonId)))

  override def saveProgress(userId: UUID, progress: PlaybackProgress): IO[PlaybackProgress] =
    sessions.use(_.unique(upsertProgress)((userId, progress)))

  override def findProgress(
      userId: UUID,
      courseId: CourseId,
      lessonId: LessonId
  ): IO[Option[PlaybackProgress]] =
    sessions.use(_.option(selectProgress)((userId, courseId, lessonId)))

  override def listProgress(
      userId: UUID,
      limit: PageLimit,
      offset: PageOffset,
      courseId: Option[CourseId],
      completed: Option[Boolean]
  ): IO[PlaybackProgressPage] =
    sessions.use { session =>
      session.execute(selectProgressPage)((userId, limit, offset, courseId, completed)).map {
        rows =>
          PlaybackProgressPage(rows.flatMap(_._2), rows.head._1, limit, offset)
      }
    }

  override def addFavorite(
      userId: UUID,
      courseId: CourseId,
      createdAt: Timestamp
  ): IO[Option[Favorite]] =
    sessions.use(_.option(insertFavorite)((userId, courseId, createdAt)))

  override def removeFavorite(userId: UUID, courseId: CourseId): IO[Unit] =
    sessions.use(_.execute(deleteFavorite)((userId, courseId)).void)

  override def listFavorites(
      userId: UUID,
      limit: PageLimit,
      offset: PageOffset
  ): IO[FavoritePage] =
    sessions.use { session =>
      session.execute(selectFavoritesPage)((userId, limit, offset)).map { rows =>
        FavoritePage(rows.flatMap(_._2), rows.head._1, limit, offset)
      }
    }

object PlaybackRepositoryImpl:

  private val courseId: Codec[CourseId] =
    uuid.imap(CourseId(_))(CourseId.value)

  private val lessonId: Codec[LessonId] =
    text.eimap(LessonId(_))(LessonId.value)

  private val lessonTitle: Codec[LessonTitle] =
    text.eimap(LessonTitle(_))(LessonTitle.value)

  private val durationSeconds: Codec[DurationSeconds] =
    int4.eimap(DurationSeconds(_))(DurationSeconds.value)

  private val objectKey: Codec[ObjectKey] =
    text.eimap(ObjectKey(_))(ObjectKey.value)

  private val positionSeconds: Codec[PositionSeconds] =
    int4.eimap(PositionSeconds(_))(PositionSeconds.value)

  private val timestamp: Codec[Timestamp] =
    timestamptz.imap(Timestamp.fromOffsetDateTime)(_.toOffsetDateTime)

  private val pageLimit: Codec[PageLimit] =
    int4.eimap(PageLimit(_))(PageLimit.value)

  private val pageOffset: Codec[PageOffset] =
    int4.eimap(PageOffset(_))(PageOffset.value)

  private val totalCount: Codec[TotalCount] =
    int8.eimap(TotalCount(_))(TotalCount.value)

  private val lesson: Codec[Lesson] =
    (courseId *: lessonId *: lessonTitle *: durationSeconds *: bool.product(objectKey)).to[Lesson]

  private val progress: Codec[PlaybackProgress] =
    (courseId *: lessonId *: positionSeconds *: bool *: timestamp).to[PlaybackProgress]

  private val favorite: Codec[Favorite] =
    (courseId *: timestamp).to[Favorite]

  private val userProgress: Codec[(UUID, PlaybackProgress)] = uuid *: progress

  private val favoriteValues: Codec[(UUID, CourseId, Timestamp)] = uuid *: courseId *: timestamp

  private val progressFilter: Codec[
    (UUID, PageLimit, PageOffset, Option[CourseId], Option[Boolean])
  ] = uuid *: pageLimit *: pageOffset *: courseId.opt *: bool.opt

  private val favoriteFilter: Codec[(UUID, PageLimit, PageOffset)] =
    uuid *: pageLimit.product(pageOffset)

  private val selectUserExists: Query[UUID, Boolean] =
    sql"SELECT EXISTS (SELECT 1 FROM users WHERE id = $uuid)".query(bool)

  private val selectCourseExists: Query[CourseId, Boolean] =
    sql"SELECT EXISTS (SELECT 1 FROM lessons WHERE course_id = $courseId)".query(bool)

  private val selectLesson: Query[(CourseId, LessonId), Lesson] =
    sql"""
      SELECT course_id, lesson_id, title, duration_seconds, is_preview, object_key
      FROM lessons
      WHERE course_id = $courseId AND lesson_id = $lessonId
    """.query(lesson)

  private val upsertProgress: Query[(UUID, PlaybackProgress), PlaybackProgress] =
    sql"""
      INSERT INTO playback_progress (
        user_id, course_id, lesson_id, position_seconds, completed, updated_at
      )
      VALUES ($userProgress)
      ON CONFLICT (user_id, course_id, lesson_id) DO UPDATE SET
        position_seconds = EXCLUDED.position_seconds,
        completed = EXCLUDED.completed,
        updated_at = EXCLUDED.updated_at
      RETURNING course_id, lesson_id, position_seconds, completed, updated_at
    """.query(progress)

  private val selectProgress: Query[(UUID, CourseId, LessonId), PlaybackProgress] =
    sql"""
      SELECT course_id, lesson_id, position_seconds, completed, updated_at
      FROM playback_progress
      WHERE user_id = $uuid AND course_id = $courseId AND lesson_id = $lessonId
    """.query(progress)

  private val selectProgressPage: Query[
    (UUID, PageLimit, PageOffset, Option[CourseId], Option[Boolean]),
    (TotalCount, Option[PlaybackProgress])
  ] =
    sql"""
      WITH filter (user_id, page_limit, page_offset, course_id, completed) AS (
        VALUES ($progressFilter)
      ),
      filtered_progress AS (
        SELECT progress.course_id, progress.lesson_id, progress.position_seconds,
               progress.completed, progress.updated_at
        FROM playback_progress progress
        CROSS JOIN filter
        WHERE progress.user_id = filter.user_id
          AND (filter.course_id IS NULL OR progress.course_id = filter.course_id)
          AND (filter.completed IS NULL OR progress.completed = filter.completed)
      ),
      page AS (
        SELECT * FROM filtered_progress
        ORDER BY updated_at DESC, course_id, lesson_id
        LIMIT (SELECT page_limit FROM filter)
        OFFSET (SELECT page_offset FROM filter)
      )
      SELECT total.value, page.course_id, page.lesson_id, page.position_seconds,
             page.completed, page.updated_at
      FROM (SELECT count(*) AS value FROM filtered_progress) total
      LEFT JOIN page ON TRUE
      ORDER BY page.updated_at DESC, page.course_id, page.lesson_id
    """.query(totalCount *: progress.opt)

  private val insertFavorite: Query[(UUID, CourseId, Timestamp), Favorite] =
    sql"""
      WITH input (user_id, course_id, created_at) AS (
        VALUES ($favoriteValues)
      )
      INSERT INTO favorites (user_id, course_id, created_at)
      SELECT input.user_id, input.course_id, input.created_at
      FROM input
      WHERE EXISTS (SELECT 1 FROM lessons WHERE lessons.course_id = input.course_id)
      ON CONFLICT (user_id, course_id) DO UPDATE SET
        created_at = favorites.created_at
      RETURNING course_id, created_at
    """.query(favorite)

  private val deleteFavorite: Command[(UUID, CourseId)] =
    sql"DELETE FROM favorites WHERE user_id = $uuid AND course_id = $courseId".command

  private val selectFavoritesPage: Query[
    (UUID, PageLimit, PageOffset),
    (TotalCount, Option[Favorite])
  ] =
    sql"""
      WITH filter (user_id, page_limit, page_offset) AS (
        VALUES ($favoriteFilter)
      ),
      filtered_favorites AS (
        SELECT favorite.course_id, favorite.created_at
        FROM favorites favorite
        CROSS JOIN filter
        WHERE favorite.user_id = filter.user_id
      ),
      page AS (
        SELECT * FROM filtered_favorites
        ORDER BY created_at DESC, course_id
        LIMIT (SELECT page_limit FROM filter)
        OFFSET (SELECT page_offset FROM filter)
      )
      SELECT total.value, page.course_id, page.created_at
      FROM (SELECT count(*) AS value FROM filtered_favorites) total
      LEFT JOIN page ON TRUE
      ORDER BY page.created_at DESC, page.course_id
    """.query(totalCount *: favorite.opt)
