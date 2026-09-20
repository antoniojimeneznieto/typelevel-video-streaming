package org.typelevel.video.streaming.backend.catalog

import java.util.UUID

import cats.effect.IO
import org.http4s.{DecodeResult, EntityDecoder, MalformedMessageBodyFailure, MediaType}
import org.typelevel.video.streaming.backend.catalog.domain.*
import org.typelevel.video.streaming.backend.runtime.postgres.SkunkSpec
import smithy4s.{Blob, Schema}
import smithy4s.json.Json

trait CatalogFixture extends SkunkSpec:

  ///////////////////////////////////////////////////////////////////////////////
  // test data
  ///////////////////////////////////////////////////////////////////////////////

  protected val catsEffect = Course(
    id           = CourseId(new UUID(0L, 1L)),
    slug         = valid(CourseSlug("cats-effect")),
    title        = valid(CourseTitle("Cats Effect 3")),
    description  = valid(CourseDescription("Practical concurrency with fibers.")),
    level        = CourseLevel.INTERMEDIATE,
    kind         = CourseKind.TALK,
    topic        = valid(Topic("Effects & Concurrency")),
    technologies = List(valid(Technology("Scala")), valid(Technology("Cats Effect"))),
    instructor   =
      Instructor(valid(InstructorName("Daniel Spiewak")), Some(valid(InstructorRole("Speaker")))),
    durationSeconds = Some(valid(DurationSeconds(2370))),
    lessonCount     = Some(valid(LessonCount(1))),
  )

  protected val fs2 = catsEffect.copy(
    id              = CourseId(new UUID(0L, 2L)),
    slug            = valid(CourseSlug("fs2-streaming")),
    title           = valid(CourseTitle("FS2 Streaming")),
    description     = valid(CourseDescription("Functional streams and chunks.")),
    topic           = valid(Topic("Streaming")),
    technologies    = List(valid(Technology("FS2"))),
    instructor      = Instructor(valid(InstructorName("Michael Pilquist"))),
    durationSeconds = Some(valid(DurationSeconds(2874))),
  )

  protected val scalaBasics = catsEffect.copy(
    id              = CourseId(new UUID(0L, 3L)),
    slug            = valid(CourseSlug("scala-basics")),
    title           = valid(CourseTitle("Scala Basics")),
    description     = valid(CourseDescription("Getting started with Scala.")),
    level           = CourseLevel.BEGINNER,
    kind            = CourseKind.COURSE,
    topic           = valid(Topic("Language")),
    technologies    = Nil,
    instructor      = Instructor(valid(InstructorName("Alex"))),
    durationSeconds = None,
    lessonCount     = None,
  )

  protected val discover = LearningPath(
    id          = valid(LearningPathId("discover-typelevel")),
    title       = valid(LearningPathTitle("Discover Typelevel")),
    description = valid(LearningPathDescription("Explore the Typelevel ecosystem.")),
    timeLabel   = valid(TimeLabel("2 hours")),
    level       = CourseLevel.BEGINNER,
    tone        = LearningPathTone.YELLOW,
    courseIds   = List(fs2.id, catsEffect.id),
  )

  protected val inside = discover.copy(
    id          = valid(LearningPathId("inside-typelevel")),
    title       = valid(LearningPathTitle("Inside Typelevel")),
    description = valid(LearningPathDescription("Explore effects and streaming.")),
    timeLabel   = valid(TimeLabel("1 hour")),
    level       = CourseLevel.INTERMEDIATE,
    tone        = LearningPathTone.PURPLE,
    courseIds   = Nil,
  )

  protected val coursePage = CoursePage(
    List(catsEffect, fs2, scalaBasics),
    valid(TotalCount(3)),
    valid(PageLimit(20)),
    valid(PageOffset(0)),
  )

  protected val learningPathPage = LearningPathPage(
    List(discover, inside),
    valid(TotalCount(2)),
    valid(PageLimit(20)),
    valid(PageOffset(0)),
  )

  protected val courseFilter = CourseFilter(
    limit      = valid(PageLimit(1)),
    offset     = valid(PageOffset(0)),
    query      = Some(valid(SearchQuery("FIBERS"))),
    level      = Some(CourseLevel.INTERMEDIATE),
    kind       = Some(CourseKind.TALK),
    topic      = Some(valid(Topic("effects & concurrency"))),
    technology = Some(valid(Technology("cats effect"))),
  )

  protected val learningPathFilter = LearningPathFilter(
    limit  = valid(PageLimit(1)),
    offset = valid(PageOffset(0)),
    query  = Some(valid(SearchQuery("DISCOVER"))),
    level  = Some(CourseLevel.BEGINNER),
    tone   = Some(LearningPathTone.YELLOW),
  )

  protected val filteredCoursePage = CoursePage(
    List(catsEffect),
    valid(TotalCount(1)),
    courseFilter.limit,
    courseFilter.offset,
  )

  protected val filteredLearningPathPage = LearningPathPage(
    List(discover),
    valid(TotalCount(1)),
    learningPathFilter.limit,
    learningPathFilter.offset,
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

  override protected val initScript: String = "sql/catalog.sql"

  ///////////////////////////////////////////////////////////////////////////////
  // validation helper
  ///////////////////////////////////////////////////////////////////////////////

  protected def valid[A](value: Either[String, A]): A =
    value.fold(message => throw new AssertionError(message), identity)
