package typelevel.courses.routing

import munit.FunSuite
import org.http4s.syntax.all.*

final class AppRouteSuite extends FunSuite:
  test("parses the main workshop routes") {
    assertEquals(AppRoute.parse(uri"/"), AppRoute.Landing)
    assertEquals(AppRoute.parse(uri"/browse"), AppRoute.Browse)
    assertEquals(
      AppRoute.parse(uri"/course/fs2-chunk"),
      AppRoute.Course("fs2-chunk"),
    )
    assertEquals(
      AppRoute.parse(uri"/watch/fs2-chunk/lesson-1"),
      AppRoute.Watch("fs2-chunk", "lesson-1"),
    )
    assertEquals(AppRoute.parse(uri"/unknown"), AppRoute.NotFound)
  }

  test("accepts only local return destinations") {
    assert(AppRoute.isSafeReturnPath("/course/fs2-streaming?q=1"))
    assert(!AppRoute.isSafeReturnPath("//example.com/steal"))
    assert(!AppRoute.isSafeReturnPath("https://example.com"))
  }

  test("login preserves the requested destination") {
    val destination = uri"/search?q=effects#results"
    val login       = AppRoute.Login(Some(AppRoute.safeReturnPath(destination)))
    assertEquals(AppRoute.parse(login.uri), AppRoute.Login(Some("/search?q=effects#results")))
  }

  test("learning pages require authentication") {
    assert(AppRoute.isProtected(uri"/browse"))
    assert(AppRoute.isProtected(uri"/watch/fs2-chunk/lesson-1"))
    assert(!AppRoute.isProtected(uri"/"))
    assert(!AppRoute.isProtected(uri"/login"))
  }
