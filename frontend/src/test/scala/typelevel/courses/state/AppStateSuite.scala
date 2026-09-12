package typelevel.courses.state

import cats.effect.IO
import cats.effect.std.Queue
import fs2.concurrent.SignallingRef
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import typelevel.courses.api.{Favorite, PlaybackProgress}
import typelevel.courses.data.Catalog

final class AppStateSuite extends CatsEffectSuite:
  private val video  = Catalog.courses.find(_.slug == "threads-at-scale").get
  private val lesson = video.lessons.head

  test("anonymous state contains no device-local learning data") {
    val state = AppState.anonymous

    assertEquals(state.authStatus, AuthStatus.Anonymous)
    assertEquals(state.user, None)
    assertEquals(state.saved, Set.empty)
    assertEquals(state.progress, Map.empty)
    assertEquals(state.completedLessons, Set.empty)
    assertEquals(state.playbackStatus, RemoteStateStatus.Idle)
  }

  test("a lower server resume position replaces progress and clears completion") {
    val completed = AppState.view(
      AppStateData(playbackProgress = Vector(progress(lesson.durationSeconds, completed = true))),
      Catalog.courses
    )
    val rewound = AppState.view(
      completed.data.copy(playbackProgress = Vector(progress(300, completed = false))),
      Catalog.courses
    )

    assertEquals(completed.progress(video.id), 100)
    assert(completed.completedLessons.contains(AppState.completedKey(video.id, lesson.id)))
    assertEquals(rewound.progress(video.id), (300.0 / lesson.durationSeconds * 100).floor.toInt)
    assert(!rewound.completedLessons.contains(AppState.completedKey(video.id, lesson.id)))
  }

  test("recent courses preserve the backend's newest-first order without duplicates") {
    val other = Catalog.courses.find(_.slug == "typelevel-retrospective").get
    val items = Vector(
      progress(10, completed = false),
      PlaybackProgress(other.id, other.lessons.head.id, 20, false, "2026-09-08T09:00:00Z"),
      progress(30, completed = false)
    )
    val state = AppState.view(AppStateData(playbackProgress = items), Catalog.courses)

    assertEquals(state.recentCourseIds, Vector(video.id, other.id))
  }

  test("favorites expose backend course UUIDs as saved content") {
    val items = Vector(
      Favorite(video.id, "2026-09-08T10:00:00Z"),
      Favorite("another-course", "2026-09-08T09:00:00Z")
    )
    val state = AppState.view(AppStateData(favorites = items), Catalog.courses)

    assertEquals(state.saved, Set(video.id, "another-course"))
  }

  test("combined playback status and errors retain independent service results") {
    val state = AppState.view(
      AppStateData(
        progressStatus        = RemoteStateStatus.Error,
        favoritesStatus       = RemoteStateStatus.Ready,
        progressSyncError     = Some("progress unavailable"),
        favoriteMutationError = Some("favorite unavailable")
      ),
      Catalog.courses
    )

    assertEquals(state.playbackStatus, RemoteStateStatus.Error)
    assertEquals(state.playbackError, Some("progress unavailable"))
    assertEquals(state.favoritesError, Some("favorite unavailable"))
  }

  test("watching the full duration without completion remains capped at 99 percent") {
    val state = AppState.view(
      AppStateData(playbackProgress =
        Vector(progress(lesson.durationSeconds + 10, completed = false))
      ),
      Catalog.courses
    )

    assertEquals(state.progress(video.id), 99)
    assertEquals(state.completedLessons, Set.empty)
  }

  test("source-only updates derive saved and learning state without a background subscriber") {
    for
      data    <- SignallingRef[IO].of(AppStateData())
      courses <- SignallingRef[IO].of(Catalog.courses)
      state    = AppState.signal(data, courses)
      _       <- data.update(
             _.copy(
               favorites        = Vector(Favorite(video.id, "2026-09-08T10:00:00Z")),
               playbackProgress = Vector(progress(lesson.durationSeconds, completed = true))
             )
           )
      completed <- state.get
      _         <- data.update(_.copy(playbackProgress = Vector(progress(300, completed = false))))
      rewound   <- state.get
      _         <- data.set(AppStateData())
      cleared   <- state.get
    yield
      assertEquals(completed.saved, Set(video.id))
      assertEquals(completed.recentCourseIds, Vector(video.id))
      assertEquals(completed.completedLessons, Set(AppState.completedKey(video.id, lesson.id)))
      assertEquals(completed.progress(video.id), 100)
      assertEquals(rewound.saved, completed.saved)
      assertEquals(rewound.completedLessons, Set.empty)
      assertEquals(rewound.progress(video.id), (300.0 / lesson.durationSeconds * 100).floor.toInt)
      assertEquals(cleared, AppState.anonymous)
  }

  test("favorite source rollback derives saved IDs without disturbing learning state or errors") {
    val favorite = Favorite(video.id, "2026-09-08T10:00:00Z")
    val another  = Favorite("another-course", "2026-09-08T09:00:00Z")
    val initial  = AppStateData(
      favorites         = Vector(another),
      playbackProgress  = Vector(progress(300, completed = false)),
      progressSyncError = Some("progress unavailable")
    )

    for
      data    <- SignallingRef[IO].of(initial)
      courses <- SignallingRef[IO].of(Catalog.courses)
      state    = AppState.signal(data, courses)
      _       <- data.update(current =>
             current.copy(
               favorites          = favorite +: current.favorites,
               pendingFavoriteIds = Set(video.id)
             )
           )
      optimistic <- state.get
      _          <- data.update(current =>
             current.copy(
               favorites             = current.favorites.filterNot(_.courseId == video.id),
               pendingFavoriteIds    = Set.empty,
               favoriteMutationError = Some("favorite unavailable")
             )
           )
      rolledBack <- state.get
    yield
      assertEquals(optimistic.saved, Set(video.id, another.courseId))
      assertEquals(optimistic.pendingFavoriteIds, Set(video.id))
      assertEquals(rolledBack.saved, Set(another.courseId))
      assertEquals(rolledBack.pendingFavoriteIds, Set.empty)
      assertEquals(rolledBack.progress, optimistic.progress)
      assertEquals(rolledBack.recentCourseIds, optimistic.recentCourseIds)
      assertEquals(rolledBack.progressError, Some("progress unavailable"))
      assertEquals(rolledBack.favoritesError, Some("favorite unavailable"))
  }

  test("catalog duration changes emit revised percentages without rewriting source state") {
    val short   = video.copy(lessons = Vector(lesson.copy(durationSeconds = 100)))
    val long    = video.copy(lessons = Vector(lesson.copy(durationSeconds = 200)))
    val initial = AppStateData(playbackProgress = Vector(progress(50, completed = false)))

    for
      data     <- SignallingRef[IO].of(initial)
      courses  <- SignallingRef[IO].of(Vector(short))
      observed <- Queue.unbounded[IO, AppState]
      state     = AppState.signal(data, courses)
      _        <- state.discrete.evalMap(observed.offer).compile.drain.background.use { _ =>
             for
               first    <- observed.take
               _        <- courses.set(Vector(long))
               revised  <- observed.take
               _        <- courses.set(Vector.empty)
               absent   <- observed.take
               _        <- courses.set(Vector(short))
               restored <- observed.take
               source   <- data.get
             yield
               assertEquals(first.progress(video.id), 50)
               assertEquals(revised.progress(video.id), 25)
               assertEquals(absent.progress, Map.empty)
               assertEquals(absent.recentCourseIds, Vector(video.id))
               assertEquals(restored.progress, first.progress)
               assertEquals(source, initial)
           }
    yield ()
  }

  test("derived auth-session codecs preserve the stored JSON contract") {
    val session = StoredAuthSession("access-token", 1788861600000d)

    assertEquals(
      session.asJson,
      Json.obj(
        "accessToken" -> Json.fromString("access-token"),
        "expiresAt" -> Json.fromDoubleOrNull(1788861600000d)
      )
    )
    assertEquals(decode[StoredAuthSession](session.asJson.noSpaces), Right(session))
  }

  private def progress(position: Int, completed: Boolean): PlaybackProgress =
    PlaybackProgress(
      courseId        = video.id,
      lessonId        = lesson.id,
      positionSeconds = position,
      completed       = completed,
      updatedAt       = "2026-09-08T10:00:00Z"
    )
