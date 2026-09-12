package typelevel.courses.data

import munit.FunSuite
import typelevel.courses.domain.{CourseFormat, CourseLevel, LearningPath, PathTone}

final class CatalogSuite extends FunSuite:
  test("catalog keeps stable unique slugs and lesson invariants") {
    assertEquals(Catalog.courses.size, 5)
    assertEquals(Catalog.courses.map(_.slug).distinct.size, Catalog.courses.size)
    assertEquals(Catalog.courses.map(_.lessons.size).sum, 5)
    assert(Catalog.courses.forall(_.lessons.nonEmpty))
    assert(Catalog.courses.forall(_.outcomes.size >= 3))
    assert(Catalog.courses.forall(course => course.lessonCount == course.lessons.size))
    assert(Catalog.courses.forall(_.lessons.head.preview))
    assert(Catalog.courses.forall(_.lessons.drop(1).forall(!_.preview)))
  }

  test("community videos are modeled as one playable sourced lesson") {
    val videos = Catalog.courses.filter(_.format == CourseFormat.Video)

    assertEquals(videos.size, 5)
    videos.foreach { video =>
      assertEquals(video.lessonCount, 1)
      assertEquals(video.lessons.size, 1)
      assertEquals(video.lessons.head.id, "lesson-1")
      assertEquals(video.durationSeconds, Some(video.lessons.head.durationSeconds))
      assert(video.thumbnail.nonEmpty)
      assert(video.source.exists(_.url.startsWith("https://www.youtube.com/watch?v=")))
    }
  }

  test("every learning path points at existing courses") {
    assertEquals(Catalog.validationErrors, Vector.empty)
    assert(Catalog.learningPaths.forall(_.courseIds.nonEmpty))
  }

  test("fallback paths match the current backend metadata and ordered memberships") {
    assertEquals(
      Catalog.learningPaths,
      Vector(
        LearningPath(
          id          = "discover-typelevel",
          title       = "Discover Typelevel",
          description = "Explore the ecosystem, its community, and its core ideas.",
          courseIds   = Vector(
            "00000000-0000-0000-0000-000000000101",
            "00000000-0000-0000-0000-000000000102"
          ),
          time  = "1 hour 15 minutes",
          level = CourseLevel.Beginner,
          tone  = PathTone.Yellow
        ),
        LearningPath(
          id          = "inside-typelevel",
          title       = "Inside Typelevel",
          description =
            "Explore the design decisions behind effects, concurrency, streaming, and error handling.",
          courseIds = Vector(
            "00000000-0000-0000-0000-000000000104",
            "00000000-0000-0000-0000-000000000105",
            "00000000-0000-0000-0000-000000000106"
          ),
          time  = "1 hour 52 minutes",
          level = CourseLevel.Intermediate,
          tone  = PathTone.Purple
        )
      )
    )
    assertEquals(Catalog.topics, "All topics" +: Catalog.courses.map(_.topic).distinct)
  }

  test("catalog and learning paths use backend course UUIDs") {
    val ids = Catalog.courses.map(_.id).toSet
    assertEquals(
      ids,
      Vector(101, 102, 104, 105, 106).map(index => f"00000000-0000-0000-0000-$index%012d").toSet
    )
    assert(Catalog.learningPaths.flatMap(_.courseIds).forall(ids.contains))
  }
