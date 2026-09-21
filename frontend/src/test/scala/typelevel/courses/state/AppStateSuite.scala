package typelevel.courses.state

import munit.FunSuite
import org.typelevel.video.streaming.backend.playback.domain.{
  CourseId,
  Favorite,
  LessonId,
  PlaybackProgress,
  PositionSeconds,
}
import smithy4s.time.Timestamp
import typelevel.courses.data.Catalog

final class AppStateSuite extends FunSuite:
  private val seed   = Catalog.courses.head
  private val course = seed.copy(lessons =
    Vector(
      seed.lessons.head.copy(id = "lesson-1", durationSeconds = 100),
      seed.lessons.head.copy(id = "lesson-2", durationSeconds = 300),
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
    val otherId = CourseId(Catalog.courses(1).course.id.value)
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

  private def view(data: AppStateData): AppState = AppState.view(data, Vector(course))

  private def progress(lessonId: String, position: Int): PlaybackProgress =
    PlaybackProgress(
      courseId,
      LessonId.unsafeApply(lessonId),
      PositionSeconds.unsafeApply(position),
      completed = false,
      updatedAt = updatedAt,
    )
