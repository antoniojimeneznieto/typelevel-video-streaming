package org.typelevel.video.streaming.backend.gateway

import scala.concurrent.duration.*

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import fs2.Stream
import org.http4s.{Request, Response, Status, Uri}
import org.http4s.client.Client
import org.http4s.implicits.uri
import weaver.SimpleIOSuite

object GatewayDeadlineSuite extends SimpleIOSuite:
  private val config = UpstreamConfig(
    uri"http://identity",
    uri"http://catalog",
    uri"http://playback",
    requestTimeout = 100.millis,
  )
  private val request = Request[IO](uri = uri"http://localhost/api/catalog/courses")

  test("native deadline returns 504 and cancels partial acquisition for each upstream") {
    List("identity", "catalog", "playback")
      .traverse { service =>
        for
          releases <- Ref.of[IO, Int](0)
          client    = Client[IO](_ =>
                     Resource.make(IO.unit)(_ => releases.update(_ + 1)) *>
                       Resource.eval(IO.never[Response[IO]]),
                   )
          response <-
            GatewayRoutes(client, config)
              .run(Request[IO](uri = Uri.unsafeFromString(s"http://localhost/api/$service/test")))
              .timeout(5.seconds)
          count <- releases.get
        yield expect(response.status == Status.GatewayTimeout) && expect(count == 1)
      }
      .map(_.reduce(_ && _))
  }

  test("response bodies can complete after the handler deadline and release resources") {
    for
      releases <- Ref.of[IO, Int](0)
      client    = Client[IO](_ =>
                 Resource.make(
                   IO.pure(
                     Response[IO]().withBodyStream(
                       Stream.sleep_[IO](200.millis) ++ Stream.emits("ok".getBytes.toSeq).covary[IO],
                     ),
                   ),
                 )(_ => releases.update(_ + 1)),
               )
      response <- GatewayRoutes(client, config).run(request)
      body     <- response.as[String].timeout(5.seconds)
      count    <- releases.get
    yield expect(response.status == Status.Ok) && expect(body == "ok") && expect(count == 1)
  }

  test("healthy responses retain their status and body") {
    val client = Client[IO](_ => Resource.pure(Response[IO](Status.Created).withEntity("ok")))
    GatewayRoutes(client, config).run(request).flatMap { response =>
      response
        .as[String]
        .map(body => expect(response.status == Status.Created) && expect(body == "ok"))
    }
  }

  test("request timeout configuration rejects nonpositive and infinite durations") {
    IO.pure(
      expect.all(
        AppConfig.positiveDuration.decode(None, "10 seconds") == Right(10.seconds),
        AppConfig.positiveDuration.decode(None, "0 seconds").isLeft,
        AppConfig.positiveDuration.decode(None, "-1 second").isLeft,
        AppConfig.positiveDuration.decode(None, "Inf").isLeft,
        AppConfig.positiveDuration.decode(None, "invalid").isLeft,
      ),
    )
  }
