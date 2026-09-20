package org.typelevel.video.streaming.backend.playback

import java.util.UUID

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import org.http4s.headers.{Authorization, `Content-Type`}
import org.http4s.implicits.uri
import org.http4s.{AuthScheme, Credentials, Headers, HttpApp, MediaType, Method, Request, Status}
import org.typelevel.ci.CIString
import org.typelevel.video.streaming.backend.playback.api.*
import org.typelevel.video.streaming.backend.playback.domain.*
import org.typelevel.video.streaming.backend.runtime.auth.{
  BearerAuthenticationMiddleware,
  BearerTokenVerifier,
}
import org.typelevel.video.streaming.backend.runtime.context.IOLocalRequestContext
import smithy4s.http4s.SimpleRestJsonBuilder
import smithy4s.json.Json
import weaver.SimpleIOSuite

object PlaybackRoutesSuite extends SimpleIOSuite with PlaybackFixture:

  ///////////////////////////////////////////////////////////////////////////////
  // preparation
  ///////////////////////////////////////////////////////////////////////////////

  private val service: PlaybackService[IO] = new PlaybackService[IO]:
    override def getPlaybackUrl(courseId: CourseId, lessonId: LessonId): IO[PlaybackUrlResponse] =
      if lessonId.value == "unavailable" then
        IO.raiseError(PlaybackUnavailableError("Video storage is temporarily unavailable"))
      else if courseId == lesson.courseId && lessonId == lesson.lessonId then IO.pure(playback)
      else IO.raiseError(VideoNotFoundError("Video not found"))

    override def updatePlaybackProgress(
        courseId: CourseId,
        lessonId: LessonId,
        positionSeconds: PositionSeconds,
    ): IO[PlaybackProgress] =
      if courseId != lesson.courseId || lessonId != lesson.lessonId then
        IO.raiseError(VideoNotFoundError("Video not found"))
      else if positionSeconds.value > lesson.durationSeconds.value then
        IO.raiseError(InvalidPlaybackProgressError("Position must not exceed the video duration"))
      else IO.pure(progress.copy(positionSeconds = positionSeconds))

    override def listPlaybackProgress(
        limit: PageLimit,
        offset: PageOffset,
        courseId: Option[CourseId],
        completed: Option[Boolean],
    ): IO[PlaybackProgressPage] =
      val items = progressPage.items.filter { progress =>
        courseId.forall(_ == progress.courseId) && completed.forall(_ == progress.completed)
      }
      IO.pure(
        PlaybackProgressPage(
          items.drop(offset.value).take(limit.value),
          valid(TotalCount(items.size.toLong)),
          limit,
          offset,
        ),
      )

    override def addFavorite(courseId: CourseId): IO[Favorite] =
      if courseId == missingCourseId then IO.raiseError(CourseNotFoundError("Course not found"))
      else IO.pure(favorite.copy(courseId = courseId))

    override def removeFavorite(courseId: CourseId): IO[Unit] = IO.unit

    override def listFavorites(limit: PageLimit, offset: PageOffset): IO[FavoritePage] =
      IO.pure(
        favoritePage.copy(
          items  = favoritePage.items.drop(offset.value).take(limit.value),
          limit  = limit,
          offset = offset,
        ),
      )

  private val verifier = new BearerTokenVerifier[IO, UUID]:
    override def verify(token: String): IO[Option[UUID]] =
      IO.pure(Option.when(token == accessToken)(alice))

  private val routes: Resource[IO, HttpApp[IO]] =
    Resource.eval(IOLocalRequestContext.create[UUID]).flatMap { context =>
      SimpleRestJsonBuilder
        .routes(service)
        .middleware(new BearerAuthenticationMiddleware(verifier, context))
        .resource
        .map(_.orNotFound)
    }

  private val authorization   = Authorization(Credentials.Token(AuthScheme.Bearer, accessToken))
  private val lessonUri       = uri"/courses" / courseId.value.toString / "lessons" / lessonId.value
  private val favoriteUri     = uri"/favorites" / courseId.value.toString
  private val playbackRequest = Request[IO](method = Method.GET, uri = lessonUri / "playback")
    .putHeaders(authorization)
  private val progressRequest = Request[IO](method = Method.PUT, uri = lessonUri / "progress")
    .withEntity(Json.writeBlob(progressInput).toUTF8String)
    .putHeaders(authorization, `Content-Type`(MediaType.application.json))

  ///////////////////////////////////////////////////////////////////////////////
  // tests
  ///////////////////////////////////////////////////////////////////////////////

  test("GET playback returns a signed URL with a valid bearer token") {
    routes.use { app =>
      for
        response <- app(playbackRequest)
        actual   <- response.as[PlaybackUrlResponse]
      yield expect.all(
        response.status == Status.Ok,
        response.contentType.map(_.mediaType).contains(MediaType.application.json),
        actual == playback,
      )
    }
  }

  test("PUT progress decodes path labels and the request body and returns saved progress") {
    routes.use { app =>
      for
        response <- app(progressRequest)
        actual   <- response.as[PlaybackProgress]
      yield expect.all(
        response.status == Status.Ok,
        response.contentType.map(_.mediaType).contains(MediaType.application.json),
        actual == progress,
      )
    }
  }

  test("GET progress supports default pagination, course and completion filters, and offsets") {
    routes.use { app =>
      for
        response <-
          app(Request[IO](method = Method.GET, uri = uri"/progress").putHeaders(authorization))
        actual   <- response.as[PlaybackProgressPage]
        filtered <- app(
                      Request[IO](
                        method = Method.GET,
                        uri    = uri"/progress"
                          .withQueryParam("courseId", courseId.value.toString)
                          .withQueryParam("completed", false),
                      ).putHeaders(authorization),
                    )
        filteredPage <- filtered.as[PlaybackProgressPage]
        paged        <- app(
                   Request[IO](
                     method = Method.GET,
                     uri    = uri"/progress?completed=true&limit=1&offset=1",
                   ).putHeaders(authorization),
                 )
        page <- paged.as[PlaybackProgressPage]
      yield expect.all(
        response.status == Status.Ok,
        actual == progressPage,
        filtered.status == Status.Ok,
        filteredPage == progressPage.copy(items = List(progress), total = valid(TotalCount(1))),
        paged.status == Status.Ok,
        page == progressPage.copy(
          items  = List(completedProgress),
          total  = valid(TotalCount(2)),
          limit  = valid(PageLimit(1)),
          offset = valid(PageOffset(1)),
        ),
      )
    }
  }

  test("PUT favorite returns a favorite and DELETE favorite returns an empty 204") {
    routes.use { app =>
      for
        added  <- app(Request[IO](method = Method.PUT, uri = favoriteUri).putHeaders(authorization))
        actual <- added.as[Favorite]
        removed <-
          app(Request[IO](method = Method.DELETE, uri = favoriteUri).putHeaders(authorization))
        body <- removed.bodyText.compile.string
      yield expect.all(
        added.status == Status.Ok,
        actual == favorite,
        removed.status == Status.NoContent,
        body.isEmpty,
      )
    }
  }

  test("GET favorites supports default and explicit pagination") {
    routes.use { app =>
      for
        response <-
          app(Request[IO](method = Method.GET, uri = uri"/favorites").putHeaders(authorization))
        actual <- response.as[FavoritePage]
        paged  <- app(
                   Request[IO](method = Method.GET, uri = uri"/favorites?limit=1&offset=1")
                     .putHeaders(authorization),
                 )
        page <- paged.as[FavoritePage]
      yield expect.all(
        response.status == Status.Ok,
        actual == favoritePage,
        paged.status == Status.Ok,
        page == favoritePage.copy(
          items  = List(favorite),
          limit  = valid(PageLimit(1)),
          offset = valid(PageOffset(1)),
        ),
      )
    }
  }

  test("missing videos and courses return modeled 404 responses") {
    routes.use { app =>
      val missingLessonUri =
        uri"/courses" / courseId.value.toString / "lessons" / missingLessonId.value

      for
        video         <- app(playbackRequest.withUri(missingLessonUri / "playback"))
        videoError    <- video.as[VideoNotFoundError]
        progress      <- app(progressRequest.withUri(missingLessonUri / "progress"))
        progressError <- progress.as[VideoNotFoundError]
        favorite      <- app(
                      Request[IO](
                        method = Method.PUT,
                        uri    = uri"/favorites" / missingCourseId.value.toString,
                      ).putHeaders(authorization),
                    )
        favoriteError <- favorite.as[CourseNotFoundError]
      yield expect.all(
        video.status == Status.NotFound,
        videoError == VideoNotFoundError("Video not found"),
        progress.status == Status.NotFound,
        progressError == VideoNotFoundError("Video not found"),
        favorite.status == Status.NotFound,
        favoriteError == CourseNotFoundError("Course not found"),
      )
    }
  }

  test("a position beyond the video duration returns a modeled 400 response") {
    routes.use { app =>
      val input = progressInput.copy(positionSeconds = valid(PositionSeconds(301)))

      for
        response <- app(progressRequest.withEntity(Json.writeBlob(input).toUTF8String))
        actual   <- response.as[InvalidPlaybackProgressError]
      yield expect.all(
        response.status == Status.BadRequest,
        actual == InvalidPlaybackProgressError("Position must not exceed the video duration"),
      )
    }
  }

  test("unavailable storage returns a modeled 503 response") {
    routes.use { app =>
      val unavailableUri =
        uri"/courses" / courseId.value.toString / "lessons" / "unavailable" / "playback"

      for
        response <- app(playbackRequest.withUri(unavailableUri))
        actual   <- response.as[PlaybackUnavailableError]
      yield expect.all(
        response.status == Status.ServiceUnavailable,
        actual == PlaybackUnavailableError("Video storage is temporarily unavailable"),
      )
    }
  }

  test("every operation rejects missing and invalid bearer tokens with 401") {
    val requests = List(
      playbackRequest,
      progressRequest,
      Request[IO](method = Method.GET, uri    = uri"/progress"),
      Request[IO](method = Method.PUT, uri    = favoriteUri),
      Request[IO](method = Method.DELETE, uri = favoriteUri),
      Request[IO](method = Method.GET, uri    = uri"/favorites"),
    )

    routes.use { app =>
      requests
        .traverse { request =>
          for
            missing <- app(request.withHeaders(Headers.empty))
            invalid <- app(
                         request.putHeaders(
                           Authorization(Credentials.Token(AuthScheme.Bearer, "invalid-token")),
                         ),
                       )
          yield expect.all(
            missing.status == Status.Unauthorized,
            invalid.status == Status.Unauthorized,
            missing.headers.get(CIString("WWW-Authenticate")).exists(_.head.value == "Bearer"),
            invalid.headers.get(CIString("WWW-Authenticate")).exists(_.head.value == "Bearer"),
          )
        }
        .map(_.reduce(_ and _))
    }
  }

  test("invalid positions, filters, and pagination return 400") {
    val invalidPages = for
      path          <- List(uri"/progress", uri"/favorites")
      (name, value) <- List("limit" -> "0", "limit" -> "101", "limit" -> "abc", "offset" -> "-1")
    yield Request[IO](method = Method.GET, uri = path.withQueryParam(name, value))
      .putHeaders(authorization)

    val invalidRequests = List(
      progressRequest.withEntity("""{"positionSeconds":-1}"""),
      progressRequest.withEntity("{}"),
      Request[IO](method = Method.GET, uri = uri"/progress?courseId=invalid")
        .putHeaders(authorization),
      Request[IO](method = Method.GET, uri = uri"/progress?completed=invalid")
        .putHeaders(authorization),
    ) ++ invalidPages

    routes.use { app =>
      invalidRequests
        .traverse { request =>
          app(request).map(response => expect(response.status == Status.BadRequest))
        }
        .map(_.reduce(_ and _))
    }
  }
