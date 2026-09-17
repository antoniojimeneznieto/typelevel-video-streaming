package org.typelevel.video.streaming.backend.playback

import java.time.Instant
import java.util.UUID

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import org.http4s.headers.`Content-Type`
import org.http4s.{HttpApp, MediaType, Method, Request, Status, Uri}
import org.typelevel.video.streaming.backend.playback.api.{
  ListPlaybackProgressInput,
  PlaybackService,
  PlaybackServiceGen,
}
import org.typelevel.video.streaming.backend.playback.domain.*
import smithy4s.Blob
import smithy4s.http4s.SimpleRestJsonBuilder
import smithy4s.json.Json
import smithy4s.time.Timestamp
import weaver.SimpleIOSuite

object PlaybackRoutesSuite extends SimpleIOSuite:

  private val courseId     = CourseId(UUID.fromString("00000000-0000-0000-0000-000000000104"))
  private val lessonId     = valid(LessonId("lesson-1"))
  private val now          = Timestamp.fromInstant(Instant.parse("2026-09-07T10:00:00Z"))
  private val progressPath = s"/courses/${courseId.value}/lessons/${lessonId.value}/progress"
  private val favoritePath = s"/favorites/${courseId.value}"
  private val zero         = valid(TotalCount(0))

  test("PUT progress decodes path labels and JSON and returns completion and an ISO timestamp") {
    val service = new Stub:
      override def updatePlaybackProgress(
          courseId: CourseId,
          lessonId: LessonId,
          positionSeconds: PositionSeconds,
      ): IO[PlaybackProgress] =
        IO.pure(PlaybackProgress(courseId, lessonId, positionSeconds, true, now))

    routes(service).use { app =>
      for
        response <- app(progressRequest(125))
        body     <- response.as[String]
      yield expect.all(
        response.status == Status.Ok,
        Json.read[PlaybackProgress](Blob(body)) ==
          Right(PlaybackProgress(courseId, lessonId, valid(PositionSeconds(125)), true, now)),
        body.contains("\"updatedAt\":\"2026-09-07T10:00:00Z\""),
      )
    }
  }

  test("GET progress forwards filters and supplies default or explicit pagination") {
    Ref.of[IO, List[ListPlaybackProgressInput]](Nil).flatMap { calls =>
      val service = new Stub:
        override def listPlaybackProgress(
            limit: PageLimit,
            offset: PageOffset,
            courseId: Option[CourseId],
            completed: Option[Boolean],
        ): IO[PlaybackProgressPage] =
          calls.update(_ :+ ListPlaybackProgressInput(limit, offset, courseId, completed)) *>
            IO.pure(PlaybackProgressPage(Nil, zero, limit, offset))

      val expected = List(
        ListPlaybackProgressInput(courseId = Some(courseId), completed = Some(false)),
        ListPlaybackProgressInput(valid(PageLimit(2)), valid(PageOffset(3)), None, Some(true)),
      )

      routes(service).use { app =>
        for
          first <- app(request(Method.GET, s"/progress?courseId=${courseId.value}&completed=false"))
          firstBody   <- first.as[String]
          second      <- app(request(Method.GET, "/progress?completed=true&limit=2&offset=3"))
          secondBody  <- second.as[String]
          actualCalls <- calls.get
        yield expect.all(
          first.status == Status.Ok,
          second.status == Status.Ok,
          actualCalls == expected,
          Json.read[PlaybackProgressPage](Blob(firstBody)) ==
            Right(PlaybackProgressPage(Nil, zero, expected.head.limit, expected.head.offset)),
          Json.read[PlaybackProgressPage](Blob(secondBody)) ==
            Right(PlaybackProgressPage(Nil, zero, expected.last.limit, expected.last.offset)),
        )
      }
    }
  }

  test("PUT favorite returns its timestamp and DELETE favorite returns an empty 204") {
    Ref.of[IO, Option[CourseId]](None).flatMap { removed =>
      val service = new Stub:
        override def addFavorite(courseId: CourseId): IO[Favorite] =
          IO.pure(Favorite(courseId, now))

        override def removeFavorite(courseId: CourseId): IO[Unit] =
          removed.set(Some(courseId))

      routes(service).use { app =>
        for
          added         <- app(request(Method.PUT, favoritePath))
          addedBody     <- added.as[String]
          deleted       <- app(request(Method.DELETE, favoritePath))
          deletedBody   <- deleted.as[String]
          removedCourse <- removed.get
        yield expect.all(
          added.status == Status.Ok,
          Json.read[Favorite](Blob(addedBody)) == Right(Favorite(courseId, now)),
          addedBody.contains("\"createdAt\":\"2026-09-07T10:00:00Z\""),
          deleted.status == Status.NoContent,
          deletedBody.isEmpty,
          removedCourse.contains(courseId),
        )
      }
    }
  }

  test("GET favorites decodes default and explicit pagination and serializes a page") {
    val service = new Stub:
      override def listFavorites(limit: PageLimit, offset: PageOffset): IO[FavoritePage] =
        IO.pure(FavoritePage(List(Favorite(courseId, now)), valid(TotalCount(1)), limit, offset))

    routes(service).use { app =>
      List(("/favorites", 20, 0), ("/favorites?limit=1&offset=2", 1, 2))
        .traverse { case (path, limit, offset) =>
          for
            response <- app(request(Method.GET, path))
            body     <- response.as[String]
          yield expect.all(
            response.status == Status.Ok,
            Json.read[FavoritePage](Blob(body)) == Right(
              FavoritePage(
                List(Favorite(courseId, now)),
                valid(TotalCount(1)),
                valid(PageLimit(limit)),
                valid(PageOffset(offset)),
              ),
            ),
          )
        }
        .map(_.reduce(_ and _))
    }
  }

  test("invalid positions and pagination return 400 before invoking the service") {
    Ref.of[IO, Int](0).flatMap { calls =>
      val service = new PlaybackServiceGen.Default[IO](
        calls.update(_ + 1) *> IO
          .raiseError(new AssertionError("Invalid input reached the service")),
      )
      val invalidRequests = progressRequest(-1) :: (for
        path  <- List("/progress", "/favorites")
        query <- List("limit=0", "limit=101", "offset=-1")
      yield request(Method.GET, s"$path?$query"))

      routes(service).use { app =>
        for
          statuses <- invalidRequests.traverse(req => app(req).map(_.status))
          count    <- calls.get
        yield expect.all(statuses.forall(_ == Status.BadRequest), count == 0)
      }
    }
  }

  private class Stub
      extends PlaybackServiceGen.Default[IO](
        IO.raiseError(new AssertionError("Unexpected operation")),
      )

  private def routes(service: PlaybackService[IO]): Resource[IO, HttpApp[IO]] =
    SimpleRestJsonBuilder.routes(service).resource.map(_.orNotFound)

  private def request(method: Method, path: String): Request[IO] =
    Request[IO](method = method, uri = Uri.unsafeFromString(path))

  private def progressRequest(position: Int): Request[IO] =
    request(Method.PUT, progressPath)
      .withEntity(s"""{"positionSeconds":$position}""")
      .putHeaders(`Content-Type`(MediaType.application.json))

  private def valid[A](either: Either[String, A]): A =
    either.fold(message => throw new AssertionError(message), identity)
