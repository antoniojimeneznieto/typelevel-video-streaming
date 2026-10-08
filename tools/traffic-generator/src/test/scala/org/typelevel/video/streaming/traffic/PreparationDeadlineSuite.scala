package org.typelevel.video.streaming.traffic

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import fs2.Stream
import org.http4s.Response
import org.http4s.client.Client
import weaver.SimpleIOSuite

object PreparationDeadlineSuite extends SimpleIOSuite:
  test("Identity preparation cancels a stalled registration and releases acquired resources") {
    for
      releases <- Ref.of[IO, Int](0)
      client    = Client[IO](_ =>
                 Resource.make(IO.unit)(_ => releases.update(_ + 1)) *>
                   Resource.eval(IO.never[Response[IO]]),
               )
      result <- TrafficGeneratorMain
                  .prepareRequest(
                    Config(profile = TrafficProfile.Identity, setupTimeout = 100.millis),
                    client,
                  )
                  .attempt
                  .timeout(5.seconds)
      count <- releases.get
    yield expect(result.left.exists(_.isInstanceOf[TimeoutException])) && expect(count == 1)
  }

  test("Playback preparation bounds login response body consumption") {
    for
      releases <- Ref.of[IO, Int](0)
      client    = Client[IO](_ =>
                 Resource.make(
                   IO.pure(Response[IO]().withBodyStream(Stream.never[IO])),
                 )(_ => releases.update(_ + 1)),
               )
      result <- TrafficGeneratorMain
                  .prepareRequest(
                    Config(profile = TrafficProfile.Playback, setupTimeout = 100.millis),
                    client,
                  )
                  .attempt
                  .timeout(5.seconds)
      count <- releases.get
    yield expect(result.left.exists(_.isInstanceOf[TimeoutException])) && expect(count == 1)
  }

  test("setup timeout can be configured and must be positive and finite") {
    IO.pure(
      expect.all(
        Cli.command.parse(List("--setup-timeout", "2s")).exists(_.setupTimeout == 2.seconds),
        Cli.command.parse(List("--setup-timeout", "0s")).isLeft,
        Cli.command.parse(List("--setup-timeout", "infinite")).isLeft,
      ),
    )
  }
