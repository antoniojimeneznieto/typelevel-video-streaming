package org.typelevel.video.streaming.backend.catalog.repository

import cats.effect.{IO, Resource}
import org.typelevel.video.streaming.backend.catalog.domain.*
import skunk.codec.all.*
import skunk.implicits.*
import skunk.{Codec, Decoder, Query, Session}
import CatalogRepositoryImpl.*

trait CatalogRepository:

  def listCourses(filter: CourseFilter): IO[CoursePage]

  def listLearningPaths(filter: LearningPathFilter): IO[LearningPathPage]

final class CatalogRepositoryImpl(
    sessions: Resource[IO, Session[IO]],
) extends CatalogRepository:

  override def listCourses(filter: CourseFilter): IO[CoursePage] =
    sessions.allocated.flatMap { case (session, release) =>
      IO.uncancelable { poll =>
        poll {
          for
            rows  <- session.execute(selectCourses)(filter)
            total <- session.unique(countCourses)(filter)
          yield (rows, total)
        }.onCancel(release)
          .handleErrorWith(error => release *> IO.raiseError(error))
          .flatMap { (rows, total) =>
            val page = CoursePage(toCourses(rows), total, filter.limit, filter.offset)
            (if rows.nonEmpty then release else IO.unit).as(page)
          }
      }
    }

  override def listLearningPaths(filter: LearningPathFilter): IO[LearningPathPage] =
    sessions.use { session =>
      for
        rows  <- session.execute(selectLearningPaths)(filter)
        total <- session.unique(countLearningPaths)(filter)
      yield LearningPathPage(toLearningPaths(rows), total, filter.limit, filter.offset)
    }

object CatalogRepositoryImpl:

  final private case class CourseRow(
      id: CourseId,
      slug: CourseSlug,
      title: CourseTitle,
      description: CourseDescription,
      level: CourseLevel,
      kind: CourseKind,
      topic: Topic,
      instructorName: InstructorName,
      instructorRole: Option[InstructorRole],
      durationSeconds: Option[DurationSeconds],
      lessonCount: Option[LessonCount],
      technology: Option[Technology],
  ):

    def toCourse: Course =
      Course(
        id              = id,
        slug            = slug,
        title           = title,
        description     = description,
        level           = level,
        kind            = kind,
        topic           = topic,
        technologies    = technology.toList,
        instructor      = Instructor(instructorName, instructorRole),
        durationSeconds = durationSeconds,
        lessonCount     = lessonCount,
      )

  final private case class LearningPathRow(
      id: LearningPathId,
      title: LearningPathTitle,
      description: LearningPathDescription,
      timeLabel: TimeLabel,
      level: CourseLevel,
      tone: LearningPathTone,
      courseId: Option[CourseId],
  ):

    def toLearningPath: LearningPath =
      LearningPath(
        id          = id,
        title       = title,
        description = description,
        timeLabel   = timeLabel,
        level       = level,
        tone        = tone,
        courseIds   = courseId.toList,
      )

  private def toCourses(rows: List[CourseRow]): List[Course] =
    rows
      .foldLeft(List.empty[Course]) { (courses, row) =>
        courses match
          case course :: tail if course.id == row.id =>
            course.copy(technologies = course.technologies ++ row.technology) :: tail
          case _ => row.toCourse :: courses
      }
      .reverse

  private def toLearningPaths(rows: List[LearningPathRow]): List[LearningPath] =
    rows
      .foldLeft(List.empty[LearningPath]) { (learningPaths, row) =>
        learningPaths match
          case learningPath :: tail if learningPath.id == row.id =>
            learningPath.copy(courseIds = learningPath.courseIds ++ row.courseId) :: tail
          case _ => row.toLearningPath :: learningPaths
      }
      .reverse

  private val courseId: Codec[CourseId] =
    uuid.imap(CourseId(_))(CourseId.value)

  private val courseSlug: Codec[CourseSlug] =
    text.eimap(CourseSlug(_))(CourseSlug.value)

  private val courseTitle: Codec[CourseTitle] =
    text.eimap(CourseTitle(_))(CourseTitle.value)

  private val courseDescription: Codec[CourseDescription] =
    text.eimap(CourseDescription(_))(CourseDescription.value)

  private val courseLevel: Codec[CourseLevel] =
    text.eimap(value => enumValue("course level", value, CourseLevel.values))(
      _.stringValue,
    )

  private val courseKind: Codec[CourseKind] =
    text.eimap(value => enumValue("course kind", value, CourseKind.values))(
      _.stringValue,
    )

  private val topic: Codec[Topic] =
    text.eimap(Topic(_))(Topic.value)

  private val technology: Codec[Technology] =
    text.eimap(Technology(_))(Technology.value)

  private val instructorName: Codec[InstructorName] =
    text.eimap(InstructorName(_))(InstructorName.value)

  private val instructorRole: Codec[InstructorRole] =
    text.eimap(InstructorRole(_))(InstructorRole.value)

  private val durationSeconds: Codec[DurationSeconds] =
    int4.eimap(DurationSeconds(_))(DurationSeconds.value)

  private val lessonCount: Codec[LessonCount] =
    int4.eimap(LessonCount(_))(LessonCount.value)

  private val searchQuery: Codec[SearchQuery] =
    text.eimap(SearchQuery(_))(SearchQuery.value)

  private val pageLimit: Codec[PageLimit] =
    int4.eimap(PageLimit(_))(PageLimit.value)

  private val pageOffset: Codec[PageOffset] =
    int4.eimap(PageOffset(_))(PageOffset.value)

  private val totalCount: Codec[TotalCount] =
    int8.eimap(TotalCount(_))(TotalCount.value)

  private val courseFilter: Codec[CourseFilter] =
    (
      pageLimit *:
        pageOffset *:
        searchQuery.opt *:
        courseLevel.opt *:
        courseKind.opt *:
        topic.opt *:
        technology.opt
    ).to[CourseFilter]

  private val courseRow: Decoder[CourseRow] =
    (
      courseId *:
        courseSlug *:
        courseTitle *:
        courseDescription *:
        courseLevel *:
        courseKind *:
        topic *:
        instructorName *:
        instructorRole.opt *:
        durationSeconds.opt *:
        lessonCount.opt *:
        technology.opt
    ).to[CourseRow]

  private val learningPathId: Codec[LearningPathId] =
    text.eimap(LearningPathId(_))(LearningPathId.value)

  private val learningPathTitle: Codec[LearningPathTitle] =
    text.eimap(LearningPathTitle(_))(LearningPathTitle.value)

  private val learningPathDescription: Codec[LearningPathDescription] =
    text.eimap(LearningPathDescription(_))(LearningPathDescription.value)

  private val timeLabel: Codec[TimeLabel] =
    text.eimap(TimeLabel(_))(TimeLabel.value)

  private val learningPathTone: Codec[LearningPathTone] =
    text.eimap(value => enumValue("learning path tone", value, LearningPathTone.values))(
      _.stringValue,
    )

  private val learningPathRow: Decoder[LearningPathRow] =
    (
      learningPathId *:
        learningPathTitle *:
        learningPathDescription *:
        timeLabel *:
        courseLevel *:
        learningPathTone *:
        courseId.opt
    ).to[LearningPathRow]

  private val learningPathFilter: Codec[LearningPathFilter] =
    (
      pageLimit *:
        pageOffset *:
        searchQuery.opt *:
        courseLevel.opt *:
        learningPathTone.opt
    ).to[LearningPathFilter]

  private val selectCourses: Query[CourseFilter, CourseRow] =
    sql"""
      WITH filter (
        page_limit,
        page_offset,
        search_query,
        level,
        kind,
        topic,
        technology
      ) AS (
        VALUES ($courseFilter)
      ),
      filtered_courses AS (
        SELECT course.*
        FROM courses course
        CROSS JOIN filter
        WHERE
          (
            filter.search_query IS NULL
            OR strpos(lower(course.slug), lower(filter.search_query)) > 0
            OR strpos(lower(course.title), lower(filter.search_query)) > 0
            OR strpos(lower(course.description), lower(filter.search_query)) > 0
            OR strpos(lower(course.topic), lower(filter.search_query)) > 0
            OR strpos(lower(course.instructor_name), lower(filter.search_query)) > 0
            OR EXISTS (
              SELECT 1
              FROM course_technologies course_technology
              WHERE
                course_technology.course_id = course.id
                AND strpos(
                  lower(course_technology.technology),
                  lower(filter.search_query)
                ) > 0
            )
          )
          AND (filter.level IS NULL OR course.level = filter.level)
          AND (filter.kind IS NULL OR course.kind = filter.kind)
          AND (filter.topic IS NULL OR lower(course.topic) = lower(filter.topic))
          AND (
            filter.technology IS NULL
            OR EXISTS (
              SELECT 1
              FROM course_technologies course_technology
              WHERE
                course_technology.course_id = course.id
                AND lower(course_technology.technology) = lower(filter.technology)
            )
          )
      ),
      paged_courses AS (
        SELECT filtered_course.*
        FROM filtered_courses filtered_course
        ORDER BY filtered_course.title, filtered_course.id
        LIMIT (SELECT page_limit FROM filter)
        OFFSET (SELECT page_offset FROM filter)
      )
      SELECT
        course.id,
        course.slug,
        course.title,
        course.description,
        course.level,
        course.kind,
        course.topic,
        course.instructor_name,
        course.instructor_role,
        course.duration_seconds,
        course.lesson_count,
        course_technology.technology
      FROM paged_courses course
      LEFT JOIN course_technologies course_technology
        ON course_technology.course_id = course.id
      ORDER BY course.title, course.id, course_technology.position
    """.query(courseRow)

  private val countCourses: Query[CourseFilter, TotalCount] =
    sql"""
      WITH filter (
        page_limit,
        page_offset,
        search_query,
        level,
        kind,
        topic,
        technology
      ) AS (
        VALUES ($courseFilter)
      )
      SELECT count(*)
      FROM courses course
      CROSS JOIN filter
      WHERE
        (
          filter.search_query IS NULL
          OR strpos(lower(course.slug), lower(filter.search_query)) > 0
          OR strpos(lower(course.title), lower(filter.search_query)) > 0
          OR strpos(lower(course.description), lower(filter.search_query)) > 0
          OR strpos(lower(course.topic), lower(filter.search_query)) > 0
          OR strpos(lower(course.instructor_name), lower(filter.search_query)) > 0
          OR EXISTS (
            SELECT 1
            FROM course_technologies course_technology
            WHERE
              course_technology.course_id = course.id
              AND strpos(
                lower(course_technology.technology),
                lower(filter.search_query)
              ) > 0
          )
        )
        AND (filter.level IS NULL OR course.level = filter.level)
        AND (filter.kind IS NULL OR course.kind = filter.kind)
        AND (filter.topic IS NULL OR lower(course.topic) = lower(filter.topic))
        AND (
          filter.technology IS NULL
          OR EXISTS (
            SELECT 1
            FROM course_technologies course_technology
            WHERE
              course_technology.course_id = course.id
              AND lower(course_technology.technology) = lower(filter.technology)
          )
        )
    """.query(totalCount)

  private val selectLearningPaths: Query[LearningPathFilter, LearningPathRow] =
    sql"""
      WITH filter (
        page_limit,
        page_offset,
        search_query,
        level,
        tone
      ) AS (
        VALUES ($learningPathFilter)
      ),
      filtered_learning_paths AS (
        SELECT learning_path.*
        FROM learning_paths learning_path
        CROSS JOIN filter
        WHERE
          (
            filter.search_query IS NULL
            OR strpos(lower(learning_path.id), lower(filter.search_query)) > 0
            OR strpos(lower(learning_path.title), lower(filter.search_query)) > 0
            OR strpos(lower(learning_path.description), lower(filter.search_query)) > 0
          )
          AND (filter.level IS NULL OR learning_path.level = filter.level)
          AND (filter.tone IS NULL OR learning_path.tone = filter.tone)
      ),
      paged_learning_paths AS (
        SELECT filtered_learning_path.*
        FROM filtered_learning_paths filtered_learning_path
        ORDER BY filtered_learning_path.title, filtered_learning_path.id
        LIMIT (SELECT page_limit FROM filter)
        OFFSET (SELECT page_offset FROM filter)
      )
      SELECT
        learning_path.id,
        learning_path.title,
        learning_path.description,
        learning_path.time_label,
        learning_path.level,
        learning_path.tone,
        learning_path_course.course_id
      FROM paged_learning_paths learning_path
      LEFT JOIN learning_path_courses learning_path_course
        ON learning_path_course.learning_path_id = learning_path.id
      ORDER BY learning_path.title, learning_path.id, learning_path_course.position
    """.query(learningPathRow)

  private val countLearningPaths: Query[LearningPathFilter, TotalCount] =
    sql"""
      WITH filter (
        page_limit,
        page_offset,
        search_query,
        level,
        tone
      ) AS (
        VALUES ($learningPathFilter)
      )
      SELECT count(*)
      FROM learning_paths learning_path
      CROSS JOIN filter
      WHERE
        (
          filter.search_query IS NULL
          OR strpos(lower(learning_path.id), lower(filter.search_query)) > 0
          OR strpos(lower(learning_path.title), lower(filter.search_query)) > 0
          OR strpos(lower(learning_path.description), lower(filter.search_query)) > 0
        )
        AND (filter.level IS NULL OR learning_path.level = filter.level)
        AND (filter.tone IS NULL OR learning_path.tone = filter.tone)
    """.query(totalCount)

  private def enumValue[A <: smithy4s.Enumeration.Value](
      name: String,
      value: String,
      values: List[A],
  ): Either[String, A] =
    values
      .find(_.stringValue == value)
      .toRight(s"Unknown $name: $value")
