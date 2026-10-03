package org.typelevel.video.streaming.traffic

import cats.effect.IO
import weaver.SimpleIOSuite

object StatsSuite extends SimpleIOSuite:
  test("a clean reporting window remains valid after an earlier late arrival") {
    val previous = Stats(offered = 40, started = 39, completed = 39, droppedLate = 1)
    val current  = Stats(offered = 80, started = 79, completed = 79, droppedLate = 1)
    val window   = current.window(previous)
    IO.pure(expect.all(
      !current.valid,
      window.hcursor.get[Boolean]("load_valid") == Right(true),
      window.hcursor.get[Long]("offered") == Right(40L),
      window.hcursor.get[Long]("dropped_late") == Right(0L),
    ))
  }
