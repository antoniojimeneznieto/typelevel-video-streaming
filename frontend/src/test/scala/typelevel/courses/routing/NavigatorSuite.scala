package typelevel.courses.routing

import munit.FunSuite

final class NavigatorSuite extends FunSuite:
  private def shouldIntercept(
      button: Int               = 0,
      metaKey: Boolean          = false,
      ctrlKey: Boolean          = false,
      shiftKey: Boolean         = false,
      altKey: Boolean           = false,
      defaultPrevented: Boolean = false
  ): Boolean =
    Navigator.shouldIntercept(
      button,
      metaKey,
      ctrlKey,
      shiftKey,
      altKey,
      defaultPrevented
    )

  test("intercepts an unmodified primary click") {
    assert(shouldIntercept())
  }

  test("leaves modified clicks to the browser") {
    assert(!shouldIntercept(metaKey = true))
    assert(!shouldIntercept(ctrlKey = true))
    assert(!shouldIntercept(shiftKey = true))
    assert(!shouldIntercept(altKey = true))
  }

  test("leaves non-primary and already-handled clicks to the browser") {
    assert(!shouldIntercept(button = 1))
    assert(!shouldIntercept(button = 2))
    assert(!shouldIntercept(defaultPrevented = true))
  }
