package typelevel.courses.state

import munit.FunSuite
import typelevel.courses.api.{ApiCourse, ApiCourseKind, ApiCourseLevel, ApiInstructor}
import typelevel.courses.data.Catalog
import typelevel.courses.domain.CourseFormat

final class CatalogStoreSuite extends FunSuite:
  test("initial fallback contains only the five available videos and two current paths") {
    val initial = CatalogState.seeded

    assertEquals(initial.status, CatalogStatus.Loading)
    assertEquals(
      initial.courses.map(_.slug).toSet,
      Set(
        "threads-at-scale",
        "typelevel-retrospective",
        "fs2-chunk",
        "cats-effect-3",
        "rethinking-monad-transformers"
      )
    )
    assertEquals(initial.learningPaths.map(_.id), Vector("discover-typelevel", "inside-typelevel"))
  }

  test("hydration keeps trusted local video presentation and lesson metadata") {
    val hydrated = CatalogHydration
      .courses(
        Vector(
          ApiCourse(
            id              = "00000000-0000-0000-0000-000000000104",
            slug            = "legacy-threads-slug",
            title           = "Legacy title",
            description     = "Legacy description",
            level           = ApiCourseLevel.Beginner,
            kind            = ApiCourseKind.Talk,
            topic           = "Legacy topic",
            technologies    = Vector("Legacy"),
            instructor      = ApiInstructor("Legacy Speaker", Some("Legacy role")),
            durationSeconds = Some(10),
            lessonCount     = Some(7)
          )
        )
      )
      .head

    assertEquals(hydrated.slug, "threads-at-scale")
    assertEquals(hydrated.title, "Threads at Scale")
    assertEquals(hydrated.format, CourseFormat.Video)
    assertEquals(hydrated.lessonCount, 1)
    assertEquals(hydrated.lessons.map(_.id), Vector("lesson-1"))
    assertEquals(hydrated.durationSeconds, Some(1849))
    assert(hydrated.thumbnail.exists(_.endsWith("threads-at-scale-thumbnail.jpg")))
    assertEquals(hydrated, Catalog.courses.find(_.id == hydrated.id).get)
  }

  test("topics follow course changes without a separate state update") {
    val initial = CatalogState.seeded
    val course  = initial.courses.head
    val updated = initial.copy(courses =
      Vector(
        course.copy(topic = "New topic"),
        course.copy(topic = "Another topic"),
        course.copy(topic = "New topic")
      )
    )

    assertEquals(updated.topics, Vector("All topics", "New topic", "Another topic"))
    assertEquals(updated.copy(courses = Vector.empty).topics, Vector("All topics"))
  }

  test("local videos matched by slug retain presentation but use the backend identity") {
    val apiId    = "99999999-9999-9999-9999-999999999999"
    val expected = Catalog.courses.find(_.slug == "fs2-chunk").get
    val input    = ApiCourse(
      id              = apiId,
      slug            = expected.slug,
      title           = "Backend title",
      description     = "Backend description",
      level           = ApiCourseLevel.Beginner,
      kind            = ApiCourseKind.Talk,
      topic           = "Backend topic",
      technologies    = Vector.empty,
      instructor      = ApiInstructor("Backend speaker", None),
      durationSeconds = None,
      lessonCount     = None
    )

    assertEquals(CatalogHydration.courses(Vector(input)), Vector(expected.copy(id = apiId)))
  }

  test("hydration exposes backend-only catalog items without inventing lessons") {
    val hydrated = CatalogHydration
      .courses(
        Vector(
          ApiCourse(
            id              = "99999999-9999-9999-9999-999999999999",
            slug            = "new-community-talk",
            title           = "A New Community Talk",
            description     = "Published by the backend.",
            level           = ApiCourseLevel.Advanced,
            kind            = ApiCourseKind.Talk,
            topic           = "Community",
            technologies    = Vector("Scala"),
            instructor      = ApiInstructor("Ada Lovelace", None),
            durationSeconds = Some(3660),
            lessonCount     = Some(1)
          )
        )
      )
      .head

    assertEquals(hydrated.slug, "new-community-talk")
    assertEquals(hydrated.format, CourseFormat.Talk)
    assertEquals(hydrated.duration, "1h 1m")
    assertEquals(hydrated.lessonCount, 1)
    assertEquals(hydrated.lessons, Vector.empty)
    assertEquals(hydrated.instructor.initials, "AL")
  }
