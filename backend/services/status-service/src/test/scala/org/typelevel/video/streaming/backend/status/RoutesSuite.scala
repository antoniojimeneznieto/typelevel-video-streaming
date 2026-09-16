package org.typelevel.video.streaming.backend.status

import cats.effect.IO
import org.http4s.implicits.uri
import org.http4s.{Method, Request, Status}
import weaver.SimpleIOSuite

object RoutesSuite extends SimpleIOSuite:

  test("GET /health reports that the service is healthy") {
    val request = Request[IO](method = Method.GET, uri = uri"/health")

    Routes.health.orNotFound.run(request).flatMap { response =>
      response.as[String].map { body =>
        expect(response.status == Status.Ok) and expect(body == "ok")
      }
    }
  }
