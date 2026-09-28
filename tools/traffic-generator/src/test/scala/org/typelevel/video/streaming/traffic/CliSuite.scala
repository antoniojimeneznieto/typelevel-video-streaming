package org.typelevel.video.streaming.traffic

import scala.concurrent.duration.*

import cats.effect.IO
import org.http4s.implicits.uri
import weaver.SimpleIOSuite

object CliSuite extends SimpleIOSuite:
  test("omitted options retain the existing defaults") {
    IO.pure(expect(Cli.command.parse(Nil) == Right(Config())))
  }

  test("later options override earlier defaults, including wrapper-provided values") {
    val parsed =
      Cli.command.parse(List("--rate", "5", "--rate=10", "--duration", "infinite", "--duration=3s"))
    IO.pure(expect(parsed.exists(c => c.rate == 10 && c.duration.contains(3.seconds))))
  }

  test("typed options accept both separate and equals syntax") {
    val parsed = Cli.command.parse(
      List(
        "--base-url=https://gateway.test",
        "--rate=7",
        "--duration",
        "500ms",
        "--max-concurrent",
        "9",
        "--request-timeout=2s",
        "--drain-timeout",
        "3s",
        "--report-interval=100ms",
      ),
    )
    IO.pure(
      expect(
        parsed == Right(
          Config(
            baseUrl        = uri"https://gateway.test",
            rate           = 7,
            duration       = Some(500.millis),
            maxConcurrent  = 9,
            requestTimeout = 2.seconds,
            drainTimeout   = 3.seconds,
            reportInterval = 100.millis,
          ),
        ),
      ),
    )
  }

  test("help is generated from the options without a parse error") {
    val help = Cli.command.parse(List("--base-url=http://gateway.test", "--help"))
    IO.pure(
      expect(
        clue(help).left.exists(h =>
          h.errors.isEmpty &&
            h.toString.contains("--request-timeout") && h.toString.contains("--duration"),
        ),
      ),
    )
  }

  test("validation reports multiple invalid fields together") {
    val result = Cli.command.parse(List("--rate=0", "--max-concurrent=0", "--report-interval=0s"))
    IO.pure(expect(result.left.exists(h => h.errors.size == 3)))
  }

  test("malformed values and nonfinite request deadlines produce parser errors") {
    val args = List(
      List("--rate=abc"),
      List("--duration=never"),
      List("--request-timeout=Inf"),
      List("--duration=-1s"),
      List("--base-url=://bad"),
      List("unexpected"),
    )
    IO.pure(expect(args.forall(a => Cli.command.parse(a).left.exists(_.errors.nonEmpty))))
  }
