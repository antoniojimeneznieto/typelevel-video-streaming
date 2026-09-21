package typelevel.courses.state

import java.util.UUID

import munit.FunSuite
import org.typelevel.video.streaming.backend.catalog.domain.*
import typelevel.courses.data.Catalog
import typelevel.courses.ui.CatalogPresentation.*

final class CatalogStoreSuite extends FunSuite:
  private val video     = Catalog.courses.head
  private val backendId = CourseId(UUID.fromString("99999999-9999-9999-9999-999999999999"))

  test("matching a video by ID preserves curated presentation and playable lessons") {
    val incoming = video.course.copy(
      slug            = CourseSlug.unsafeApply("legacy-slug"),
      title           = CourseTitle.unsafeApply("Legacy title"),
      durationSeconds = Some(DurationSeconds.unsafeApply(10)),
      lessonCount     = Some(LessonCount.unsafeApply(7)),
    )

    assertEquals(CatalogHydration.courses(Vector(incoming)), Vector(video))
  }

  test("matching a video by slug retains presentation with the backend course ID") {
    val incoming = video.course.copy(
      id              = backendId,
      title           = CourseTitle.unsafeApply("Backend title"),
      durationSeconds = None,
      lessonCount     = None,
    )

    assertEquals(
      CatalogHydration.courses(Vector(incoming)),
      Vector(video.copy(course = video.course.copy(id = backendId))),
    )
  }

  test("backend-only courses keep shared metadata without inventing playable lessons") {
    val incoming = video.course.copy(
      id              = backendId,
      slug            = CourseSlug.unsafeApply("new-community-talk"),
      durationSeconds = None,
      lessonCount     = None,
    )
    val hydrated = CatalogHydration.courses(Vector(incoming)).head

    assertEquals(hydrated.course, incoming)
    assert(!hydrated.isVideo)
    assertEquals(hydrated.lessons, Vector.empty)
    assertEquals(hydrated.duration, "Duration coming soon")
    assertEquals(hydrated.lessonCount, 0)
  }

  test("learning paths keep backend updates in the curated display order") {
    val paths    = Catalog.learningPaths
    val updated  = paths.head.copy(title = LearningPathTitle.unsafeApply("Updated title"))
    val hydrated = CatalogHydration.learningPaths((updated +: paths.tail).reverse)

    assertEquals(hydrated.map(_.id), paths.map(_.id))
    assertEquals(hydrated.head, updated)
  }
