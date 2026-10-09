package typelevel.courses.state

import java.util.UUID

import munit.FunSuite
import org.typelevel.video.streaming.backend.catalog.domain as catalog
import org.typelevel.video.streaming.backend.playback.domain.{
  CourseId,
  Favorite,
  LessonId,
  PlaybackProgress,
  PositionSeconds,
}
import smithy4s.time.Timestamp
import typelevel.courses.ui.{ArtworkVariant, CourseView, LessonView}

final class AppStateSuite extends FunSuite:
  private val course = CourseView(
    course           = testCourse(1),
    eyebrow          = "Test course",
    shortDescription = "A course for progress tests.",
    artwork          = ArtworkVariant.Orbit,
    artLabel         = "TEST",
    lessons          = Vector(
      LessonView("lesson-1", "First lesson", 100, "First lesson description.", preview   = true),
      LessonView("lesson-2", "Second lesson", 300, "Second lesson description.", preview = false),
    ),
  )
  private val courseId  = CourseId(course.course.id.value)
  private val id        = courseId.value.toString
  private val updatedAt = Timestamp(2026, 9, 8)

  test("course progress is weighted by lesson duration") {
    val state = view(
      AppStateData(playbackProgress =
        Vector(
          progress("lesson-1", 100),
          progress("lesson-2", 150),
        ),
      ),
    )

    assertEquals(state.progress(id), 62)
  }

  test("full playback remains below 100 percent until every lesson is completed") {
    val watched = Vector(
      progress("lesson-1", 110).copy(completed = true),
      progress("lesson-2", 300),
    )
    val incomplete = view(AppStateData(playbackProgress = watched))
    val completed  = view(AppStateData(playbackProgress = watched.map(_.copy(completed = true))))

    assertEquals(incomplete.progress(id), 99)
    assertEquals(incomplete.completedLessons, Set(AppState.completedKey(id, "lesson-1")))
    assertEquals(completed.progress(id), 100)
    assertEquals(completed.completedLessons.size, 2)
  }

  test("rewinding a completed lesson updates progress and clears its completion") {
    val completed = AppStateData(playbackProgress =
      Vector(
        progress("lesson-1", 100).copy(completed = true),
        progress("lesson-2", 300).copy(completed = true),
      ),
    )
    val rewound = view(
      completed.copy(playbackProgress = progress("lesson-1", 20) +: completed.playbackProgress.tail),
    )

    assertEquals(rewound.progress(id), 80)
    assert(!rewound.completedLessons.contains(AppState.completedKey(id, "lesson-1")))
  }

  test("recent courses keep backend order without duplicates") {
    val otherId = CourseId(testCourse(2).id.value)
    val state   = view(
      AppStateData(playbackProgress =
        Vector(
          progress("lesson-1", 20),
          progress("lesson-1", 30).copy(courseId = otherId),
          progress("lesson-2", 40),
        ),
      ),
    )

    assertEquals(state.recentCourseIds, Vector(id, otherId.value.toString))
  }

  test("saved course IDs come from server favorites") {
    val state = view(AppStateData(favorites = Vector(Favorite(courseId, updatedAt))))

    assertEquals(state.saved, Set(id))
  }

  test("a progress sync failure does not hide a separate favorites failure") {
    val state = view(
      AppStateData(
        progressStatus        = RemoteStateStatus.Error,
        favoritesStatus       = RemoteStateStatus.Ready,
        progressSyncError     = Some("progress unavailable"),
        favoriteMutationError = Some("favorite unavailable"),
      ),
    )

    assertEquals(state.playbackStatus, RemoteStateStatus.Error)
    assertEquals(state.progressError, Some("progress unavailable"))
    assertEquals(state.favoritesError, Some("favorite unavailable"))
  }

  private def testCourse(id: Long): catalog.Course = catalog.Course(
    id              = catalog.CourseId(new UUID(0L, id)),
    slug            = catalog.CourseSlug.unsafeApply(s"test-course-$id"),
    title           = catalog.CourseTitle.unsafeApply(s"Test course $id"),
    description     = catalog.CourseDescription.unsafeApply("A course for progress tests."),
    level           = catalog.CourseLevel.BEGINNER,
    kind            = catalog.CourseKind.COURSE,
    topic           = catalog.Topic.unsafeApply("Tests"),
    technologies    = Nil,
    instructor      = catalog.Instructor(catalog.InstructorName.unsafeApply("Test instructor")),
    durationSeconds = Some(catalog.DurationSeconds.unsafeApply(400)),
    lessonCount     = Some(catalog.LessonCount.unsafeApply(2)),
  )

  private def view(data: AppStateData): AppState = AppState.view(data, Vector(course))

  private def progress(lessonId: String, position: Int): PlaybackProgress =
    PlaybackProgress(
      courseId,
      LessonId.unsafeApply(lessonId),
      PositionSeconds.unsafeApply(position),
      completed = false,
      updatedAt = updatedAt,
    )
