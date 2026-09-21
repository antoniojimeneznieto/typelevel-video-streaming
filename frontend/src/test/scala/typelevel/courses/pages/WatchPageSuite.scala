package typelevel.courses.pages

import java.util.UUID

import munit.FunSuite
import org.typelevel.video.streaming.backend.playback.domain.{
  CourseId,
  LessonId,
  PlaybackProgress,
  PositionSeconds,
}
import smithy4s.time.Timestamp

final class WatchPageSuite extends FunSuite:
  private def progress(position: Int, completed: Boolean = false): PlaybackProgress =
    PlaybackProgress(
      courseId        = CourseId(UUID.fromString("00000000-0000-0000-0000-000000000104")),
      lessonId        = LessonId.unsafeApply("lesson-1"),
      positionSeconds = PositionSeconds.unsafeApply(position),
      completed       = completed,
      updatedAt       = Timestamp(2026, 9, 8),
    )

  test("saved positions are whole seconds within the lesson") {
    assertEquals(WatchPage.normalizedProgressPosition(42.9, 100), 42)
    assertEquals(WatchPage.normalizedProgressPosition(-2.0, 100), 0)
    assertEquals(WatchPage.normalizedProgressPosition(101.0, 100), 100)
  }

  test("signed URLs refresh before they expire") {
    assertEquals(WatchPage.sourceRefreshDelayMillis(900), 870000L)
    assertEquals(WatchPage.sourceRefreshDelayMillis(10), 8000L)
  }

  test("server progress resumes unless the lesson is complete") {
    assertEquals(
      WatchPage.restoredPosition(Some(progress(123)), None, 1849, 1849.0),
      123.0,
    )
    assertEquals(
      WatchPage.restoredPosition(Some(progress(1849, completed = true)), None, 1849, 1849.0),
      0.0,
    )
  }

  test("a URL-refresh snapshot takes priority and is clamped to media bounds") {
    assertEquals(
      WatchPage.restoredPosition(
        Some(progress(80)),
        refreshPosition = Some(250.5),
        lessonDuration  = 200,
        mediaDuration   = 180.0,
      ),
      180.0,
    )
  }
