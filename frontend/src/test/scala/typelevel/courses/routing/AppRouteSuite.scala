package typelevel.courses.routing

import munit.FunSuite
import org.http4s.Uri

final class AppRouteSuite extends FunSuite:
  test("parses public, protected, alias, and media routes") {
    assertEquals(AppRoute.parse(Uri.unsafeFromString("/")), AppRoute.Landing)
    assertEquals(AppRoute.parse(Uri.unsafeFromString("/browse")), AppRoute.Browse)
    assertEquals(
      AppRoute.parse(Uri.unsafeFromString("/content/thinking-in-types")),
      AppRoute.Course("thinking-in-types")
    )
    assertEquals(
      AppRoute.parse(Uri.unsafeFromString("/watch/fs2-streaming/lesson-3")),
      AppRoute.Watch("fs2-streaming", "lesson-3")
    )
    assertEquals(
      AppRoute.parse(Uri.unsafeFromString("/watch/fs2-streaming")),
      AppRoute.Watch("fs2-streaming", "lesson-1")
    )
    assertEquals(AppRoute.parse(Uri.unsafeFromString("/about")), AppRoute.NotFound)
  }

  test("accepts only local return destinations") {
    assert(AppRoute.isSafeReturnPath("/course/fs2-streaming?q=1"))
    assert(!AppRoute.isSafeReturnPath("//example.com/steal"))
    assert(!AppRoute.isSafeReturnPath("https://example.com"))
  }

  test("preserves query and fragment in auth return paths") {
    val uri = Uri.unsafeFromString("/search?q=effects#results")
    assertEquals(AppRoute.safeReturnPath(uri), "/search?q=effects#results")
  }

  test("login return destinations round-trip encoded query values and fragments") {
    val returnTo = "/watch/fs2-chunk/lesson-1?query=Cats%20%2B%20FS2&offset=0#player"
    val login    = AppRoute.Login(Some(returnTo))

    assertEquals(AppRoute.parse(login.uri), login)
    assertEquals(login.uri.query.params.get("from"), Some(returnTo))
  }

  test("course aliases and default watch routes retain trailing-slash behavior") {
    assertEquals(
      AppRoute.parse(Uri.unsafeFromString("/content/typelevel-retrospective/?from=browse")),
      AppRoute.Course("typelevel-retrospective")
    )
    assertEquals(
      AppRoute.parse(Uri.unsafeFromString("/watch/fs2-chunk/?from=paths")),
      AppRoute.Watch("fs2-chunk", "lesson-1")
    )
  }

  test("classifies only application learning routes as protected") {
    assert(AppRoute.isProtected(Uri.unsafeFromString("/browse")))
    assert(AppRoute.isProtected(Uri.unsafeFromString("/content/thinking-in-types")))
    assert(AppRoute.isProtected(Uri.unsafeFromString("/watch/fs2-streaming/lesson-1")))
    assert(!AppRoute.isProtected(Uri.unsafeFromString("/")))
    assert(!AppRoute.isProtected(Uri.unsafeFromString("/login")))
    assert(!AppRoute.isProtected(Uri.unsafeFromString("/unknown")))
  }
