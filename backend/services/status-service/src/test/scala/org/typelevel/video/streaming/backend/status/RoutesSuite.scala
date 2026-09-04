package org.typelevel.video.streaming.backend.status

import cats.effect.IO
import org.http4s.Method.GET
import org.http4s.implicits.uri
import org.http4s.{Request, Status}
import weaver.SimpleIOSuite

object RoutesSuite extends SimpleIOSuite {

  test("GET /api/health reports that the service is available") {
    val request = Request[IO](method = GET, uri = uri"/api/health")

    Routes.apply.orNotFound.run(request).flatMap { response =>
      response.as[String].map { body =>
        expect(response.status == Status.Ok) and expect(body == "ok")
      }
    }
  }

}
