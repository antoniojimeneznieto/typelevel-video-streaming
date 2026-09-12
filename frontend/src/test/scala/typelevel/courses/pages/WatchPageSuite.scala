package typelevel.courses.pages

import munit.FunSuite
import typelevel.courses.api.PlaybackProgress

final class WatchPageSuite extends FunSuite:
  private def progress(position: Int, completed: Boolean = false): PlaybackProgress =
    PlaybackProgress(
      courseId        = "course-1",
      lessonId        = "lesson-1",
      positionSeconds = position,
      completed       = completed,
      updatedAt       = "2026-09-08T00:00:00Z"
    )

  test("progress positions are integral and constrained to the modeled lesson duration") {
    assertEquals(WatchPage.normalizedProgressPosition(42.9, 100), 42)
    assertEquals(WatchPage.normalizedProgressPosition(-2.0, 100), 0)
    assertEquals(WatchPage.normalizedProgressPosition(101.0, 100), 100)
  }

  test("signed URLs refresh before expiry with the same safety window as the React player") {
    assertEquals(WatchPage.sourceRefreshDelayMillis(900), 870000L)
    assertEquals(WatchPage.sourceRefreshDelayMillis(10), 8000L)
    assertEquals(WatchPage.sourceRefreshDelayMillis(1), 1000L)
    assertEquals(WatchPage.sourceRefreshDelayMillis(0), 870000L)
  }

  test("server progress resumes unless the lesson is complete") {
    assertEquals(
      WatchPage.restoredPosition(Some(progress(123)), None, 1849, 1849.0),
      123.0
    )
    assertEquals(
      WatchPage.restoredPosition(Some(progress(1849, completed = true)), None, 1849, 1849.0),
      0.0
    )
  }

  test("a URL-refresh snapshot takes priority and is clamped to media bounds") {
    assertEquals(
      WatchPage.restoredPosition(
        Some(progress(80)),
        refreshPosition = Some(250.5),
        lessonDuration  = 200,
        mediaDuration   = 180.0
      ),
      180.0
    )
  }
