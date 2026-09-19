package org.typelevel.video.streaming.backend.gateway

import cats.effect.IO
import org.http4s.implicits.uri
import org.http4s.{Method, Request}
import weaver.SimpleIOSuite

object GatewayRouteClassifierSuite extends SimpleIOSuite:

  test("classifies gateway operations from the mounted Smithy HTTP templates") {
    val cases = List(
      Request[IO](Method.GET, uri"/api/catalog/courses?limit=20") -> "/api/catalog/courses",
      Request[IO](Method.POST, uri"/api/identity/auth/login") -> "/api/identity/auth/login",
      Request[IO](Method.GET, uri"/api/playback/courses/course-1/lessons/lesson-2/playback") ->
        "/api/playback/courses/{courseId}/lessons/{lessonId}/playback",
      Request[IO](Method.DELETE, uri"/api/playback/favorites/course-1") ->
        "/api/playback/favorites/{courseId}",
    )

    val classifications =
      cases.map { case (request, expected) =>
        GatewayRouteClassifier.routes.classify(request.requestPrelude) -> expected
      }

    IO.pure(expect.all(classifications.map { case (actual, expected) =>
      actual.contains(expected)
    }*))
  }

  test("does not classify unmatched paths or methods") {
    val requests = List(
      Request[IO](Method.GET, uri"/api/catalog/not-a-route"),
      Request[IO](Method.POST, uri"/api/catalog/courses"),
      Request[IO](Method.GET, uri"/not-a-route"),
    )

    IO.pure(
      expect.all(
        requests.map(request =>
          GatewayRouteClassifier.routes.classify(request.requestPrelude).isEmpty,
        )*,
      ),
    )
  }
