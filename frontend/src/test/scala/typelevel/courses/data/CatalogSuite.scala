package typelevel.courses.data

import munit.FunSuite
import typelevel.courses.ui.CatalogPresentation.*

final class CatalogSuite extends FunSuite:
  test("fallback videos have unique identities and playable sourced lessons") {
    val courses = Catalog.courses
    assert(courses.nonEmpty)
    assertEquals(courses.map(_.course.id).distinct.size, courses.size)
    courses.foreach { video =>
      assert(video.lessons.nonEmpty)
      assertEquals(video.lessonCount, video.lessons.size)
      assert(video.lessons.forall(_.durationSeconds > 0))
      assertEquals(
        video.course.durationSeconds.map(_.value),
        Some(video.lessons.map(_.durationSeconds).sum),
      )
      assert(video.thumbnail.nonEmpty)
      assert(video.source.exists(_.url.startsWith("https://")))
    }
  }

  test("fallback paths contain valid course references and course slugs are unique") {
    assert(Catalog.learningPaths.nonEmpty)
    assert(Catalog.learningPaths.forall(_.courseIds.nonEmpty))
    assertEquals(Catalog.validationErrors, Vector.empty)
  }
