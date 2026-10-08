package typelevel.courses.data

import java.util.UUID

import munit.FunSuite
import org.typelevel.video.streaming.backend.catalog.domain.*
import typelevel.courses.ui.CatalogPresentation.*
import typelevel.courses.ui.ArtworkVariant

final class CatalogSuite extends FunSuite:
  test("the local featured recommendation retains its video identity and presentation") {
    val view = Catalog.featured

    assertEquals(view.course.id.value.toString, "00000000-0000-0000-0000-000000000104")
    assertEquals(view.course.slug.value, "threads-at-scale")
    assertEquals(view.course.title.value, "Threads at Scale")
    assertEquals(view.course.level, CourseLevel.INTERMEDIATE)
    assertEquals(view.course.durationSeconds.map(_.value), Some(1849))
    assertEquals(view.thumbnail, Some("/threads-at-scale-thumbnail.jpg"))
    assertEquals(view.artwork, ArtworkVariant.Orbit)
    assert(view.source.exists(_.url == "https://www.youtube.com/watch?v=PLApcas04V0"))
    assert(view.featured)
    assertEquals(view.lessons.map(_.id), Vector("lesson-1"))
    assertEquals(view.lessons.head.durationSeconds, 1849)
    assert(view.lessons.head.preview)
  }

  test("known presentation preserves backend course edits and derives lesson details") {
    val backend = backendCourse().copy(kind = CourseKind.WORKSHOP)
    val view    = Catalog.course(backend, 0)

    assertEquals(view.course, backend)
    assertEquals(view.shortDescription, backend.description.value)
    assertEquals(view.thumbnail, Some("/threads-at-scale-thumbnail.jpg"))
    assertEquals(view.artwork, ArtworkVariant.Orbit)
    assert(view.featured)
    assert(view.outcomes.nonEmpty)
    assert(view.prerequisites.nonEmpty)
    assertEquals(view.lessons.size, 1)
    val lesson = view.lessons.head
    assertEquals(lesson.id, "lesson-1")
    assertEquals(lesson.title, backend.title.value)
    assertEquals(lesson.description, backend.description.value)
    assertEquals(lesson.durationSeconds, backend.durationSeconds.get.value)
    assert(lesson.preview)
  }

  test("each known media ID retains its artwork, attribution and playable lesson") {
    val assets = Vector(
      104 -> "/threads-at-scale-thumbnail.jpg",
      101 -> "/typelevel-retrospective-thumbnail.jpg",
      105 -> "/fs2-chunk-thumbnail.jpg",
      102 -> "/cats-effect-3-thumbnail.jpg",
      106 -> "/rethinking-monad-transformers-thumbnail.jpg",
    )
    assets.foreach { (id, thumbnail) =>
      val view = Catalog.course(backendCourse(id), 0)
      assertEquals(view.thumbnail, Some(thumbnail))
      assert(view.source.exists(_.url.startsWith("https://")))
      assertEquals(view.lessons.map(_.id), Vector("lesson-1"))
      assert(view.isVideo)
    }
  }

  test("missing backend duration and lesson count do not restore old catalog values") {
    val backend = backendCourse().copy(durationSeconds = None, lessonCount = None)
    val view    = Catalog.course(backend, 0)

    assertEquals(view.course, backend)
    assertEquals(view.lessons.head.durationSeconds, 0)
    assertEquals(view.lessonCount, 0)
  }

  test("an unknown ID cannot inherit known media through its slug") {
    val backend = backendCourse(999).copy(slug = CourseSlug.unsafeApply("threads-at-scale"))
    val view    = Catalog.course(backend, 9)

    assertEquals(view.course, backend)
    assertEquals(view.shortDescription, backend.description.value)
    assertEquals(view.eyebrow, "Talk")
    assertEquals(view.artLabel, "BT")
    assertEquals(view.artwork, ArtworkVariant.Stream)
    assertEquals(view.lessons, Vector.empty)
    assertEquals(view.thumbnail, None)
    assertEquals(view.source, None)
    assert(!view.isVideo)
  }

  private def backendCourse(id: Int = 104): Course = Course(
    id              = CourseId(UUID.fromString(f"00000000-0000-0000-0000-$id%012d")),
    slug            = CourseSlug.unsafeApply("backend-slug"),
    title           = CourseTitle.unsafeApply("Backend title"),
    description     = CourseDescription.unsafeApply("Description returned by the backend."),
    level           = CourseLevel.ADVANCED,
    kind            = CourseKind.TALK,
    topic           = Topic.unsafeApply("Backend topic"),
    technologies    = List(Technology.unsafeApply("Backend technology")),
    instructor      = Instructor(InstructorName.unsafeApply("Backend instructor")),
    durationSeconds = Some(DurationSeconds.unsafeApply(123)),
    lessonCount     = Some(LessonCount.unsafeApply(1)),
  )
