package org.typelevel.video.streaming.backend.playback

import java.time.Instant
import java.util.UUID

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import org.http4s.headers.`Content-Type`
import org.http4s.{Header, HttpApp, MediaType, Method, Request, Status, Uri}
import org.typelevel.ci.CIString
import org.typelevel.video.streaming.backend.playback.api.{
  ListPlaybackProgressInput,
  PlaybackUrlResponse,
}
import org.typelevel.video.streaming.backend.playback.domain.*
import org.typelevel.video.streaming.backend.playback.repository.PlaybackRepository
import org.typelevel.video.streaming.backend.playback.service.PlaybackServiceImpl
import org.typelevel.video.streaming.backend.playback.storage.S3VideoStorage
import org.typelevel.video.streaming.backend.runtime.auth.{
  BearerAuthenticationMiddleware,
  BearerTokenVerifier,
}
import org.typelevel.video.streaming.backend.runtime.context.IOLocalRequestContext
import smithy4s.Blob
import smithy4s.http4s.SimpleRestJsonBuilder
import smithy4s.json.Json
import smithy4s.time.Timestamp
import weaver.SimpleIOSuite

object PlaybackServiceRoutesSuite extends SimpleIOSuite:

  private val userId   = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
  private val courseId = CourseId(UUID.fromString("00000000-0000-0000-0000-000000000104"))
  private val lessonId = valid(LessonId("lesson-1"))
  private val lesson   = Lesson(
    courseId,
    lessonId,
    valid(LessonTitle("Threads at Scale")),
    valid(DurationSeconds(120)),
    true,
    valid(ObjectKey("published/threads-at-scale.mp4")),
  )
  private val now          = Timestamp.fromInstant(Instant.parse("2026-09-07T10:00:00Z"))
  private val progressPath = s"/courses/${courseId.value}/lessons/${lessonId.value}/progress"
  private val favoritePath = s"/favorites/${courseId.value}"

  test("every operation requires bearer authentication before accessing the repository") {
    val requests = List(
      request(Method.GET, s"/courses/${courseId.value}/lessons/${lessonId.value}/playback", false),
      progressRequest(60, false),
      request(Method.GET, "/progress", false),
      request(Method.PUT, favoritePath, false),
      request(Method.DELETE, favoritePath, false),
      request(Method.GET, "/favorites", false),
    )

    routes(new PlaybackRepositoryStub {}).use { app =>
      requests.traverse(req => app(req).map(_.status)).map { statuses =>
        expect(statuses.forall(_ == Status.Unauthorized))
      }
    }
  }

  test("progress routes save the verified user's resume position and reject invalid positions") {
    Ref.of[IO, List[(UUID, PlaybackProgress)]](Nil).flatMap { saved =>
      val repository = new KnownUser:
        override def findLesson(courseId: CourseId, lessonId: LessonId): IO[Option[Lesson]] =
          IO.pure(Some(lesson))

        override def saveProgress(id: UUID, progress: PlaybackProgress): IO[PlaybackProgress] =
          saved.update(_ :+ (id, progress)).as(progress)

      routes(repository).use { app =>
        for
          completed     <- app(progressRequest(120))
          completedBody <- completed.as[String]
          rewound       <- app(progressRequest(30))
          rewoundBody   <- rewound.as[String]
          pastEnd       <- app(progressRequest(121))
          negative      <- app(progressRequest(-1))
          actual        <- saved.get
        yield expect.all(
          completed.status == Status.Ok,
          rewound.status == Status.Ok,
          pastEnd.status == Status.BadRequest,
          negative.status == Status.BadRequest,
          actual.map(_._1) == List(userId, userId),
          actual.map(_._2.courseId) == List(courseId, courseId),
          actual.map(_._2.lessonId) == List(lessonId, lessonId),
          actual.map(_._2.positionSeconds.value) == List(120, 30),
          actual.map(_._2.completed) == List(true, false),
          Json.read[PlaybackProgress](Blob(completedBody)).toOption == actual.headOption.map(_._2),
          Json.read[PlaybackProgress](Blob(rewoundBody)).toOption == actual.lastOption.map(_._2),
          completedBody.contains("\"updatedAt\":\""),
        )
      }
    }
  }

  test("progress listing forwards the verified user, filters, and pagination to the repository") {
    Ref.of[IO, List[(UUID, ListPlaybackProgressInput)]](Nil).flatMap { calls =>
      val item       = PlaybackProgress(courseId, lessonId, valid(PositionSeconds(120)), true, now)
      val repository = new KnownUser:
        override def listProgress(
            id: UUID,
            limit: PageLimit,
            offset: PageOffset,
            courseId: Option[CourseId],
            completed: Option[Boolean],
        ): IO[PlaybackProgressPage] =
          calls.update(_ :+ (id, ListPlaybackProgressInput(limit, offset, courseId, completed))) *>
            IO.pure(PlaybackProgressPage(List(item), valid(TotalCount(4)), limit, offset))

      routes(repository).use { app =>
        for
          response <- app(
                        request(
                          Method.GET,
                          s"/progress?courseId=${courseId.value}&completed=true&limit=2&offset=3",
                        ),
                      )
          body   <- response.as[String]
          actual <- calls.get
        yield expect.all(
          response.status == Status.Ok,
          actual == List(
            (
              userId,
              ListPlaybackProgressInput(
                valid(PageLimit(2)),
                valid(PageOffset(3)),
                Some(courseId),
                Some(true),
              ),
            ),
          ),
          Json.read[PlaybackProgressPage](Blob(body)) == Right(
            PlaybackProgressPage(
              List(item),
              valid(TotalCount(4)),
              valid(PageLimit(2)),
              valid(PageOffset(3)),
            ),
          ),
        )
      }
    }
  }

  test("favorite routes return the repository result and pass only the verified user") {
    for
      calls     <- Ref.of[IO, List[(String, UUID, CourseId)]](Nil)
      item       = Favorite(courseId, now)
      repository = new KnownUser:
                     override def addFavorite(
                         id: UUID,
                         courseId: CourseId,
                         createdAt: Timestamp,
                     ): IO[Option[Favorite]] =
                       calls.update(_ :+ ("add", id, courseId)).as(Some(item))

                     override def removeFavorite(id: UUID, courseId: CourseId): IO[Unit] =
                       calls.update(_ :+ ("remove", id, courseId))

                     override def listFavorites(
                         id: UUID,
                         limit: PageLimit,
                         offset: PageOffset,
                     ): IO[FavoritePage] =
                       calls
                         .update(_ :+ ("list", id, courseId))
                         .as(
                           FavoritePage(List(item), valid(TotalCount(1)), limit, offset),
                         )
      result <- routes(repository).use { app =>
                  for
                    added       <- app(request(Method.PUT, favoritePath))
                    addedBody   <- added.as[String]
                    listed      <- app(request(Method.GET, "/favorites?limit=10&offset=0"))
                    listedBody  <- listed.as[String]
                    removed     <- app(request(Method.DELETE, favoritePath))
                    removedBody <- removed.as[String]
                    actual      <- calls.get
                  yield expect.all(
                    added.status == Status.Ok,
                    Json.read[Favorite](Blob(addedBody)) == Right(item),
                    listed.status == Status.Ok,
                    Json.read[FavoritePage](Blob(listedBody)) == Right(
                      FavoritePage(
                        List(item),
                        valid(TotalCount(1)),
                        valid(PageLimit(10)),
                        valid(PageOffset(0)),
                      ),
                    ),
                    removed.status == Status.NoContent,
                    removedBody.isEmpty,
                    actual == List(
                      ("add", userId, courseId),
                      ("list", userId, courseId),
                      ("remove", userId, courseId),
                    ),
                  )
                }
    yield result
  }

  test("unknown favorites return 404 and invalid pagination never accesses the repository") {
    val repository = new KnownUser:
      override def addFavorite(
          id: UUID,
          courseId: CourseId,
          createdAt: Timestamp,
      ): IO[Option[Favorite]] = IO.pure(None)

    for
      missing <- routes(repository).use(app => app(request(Method.PUT, favoritePath)).map(_.status))
      invalid <- routes(new PlaybackRepositoryStub {}).use { app =>
                   List("/favorites?limit=101", "/progress?offset=-1")
                     .traverse(path => app(request(Method.GET, path)).map(_.status))
                 }
    yield expect.all(missing == Status.NotFound, invalid.forall(_ == Status.BadRequest))
  }

  private class KnownUser extends PlaybackRepositoryStub:
    override def userExists(id: UUID): IO[Boolean] = IO.pure(id == userId)

  private def routes(repository: PlaybackRepository): Resource[IO, HttpApp[IO]] =
    val verifier = new BearerTokenVerifier[IO, UUID]:
      override def verify(token: String): IO[Option[UUID]] =
        IO.pure(Option.when(token == "valid-token")(userId))
    val storage = new S3VideoStorage:
      override def getPlaybackUrl(objectKey: ObjectKey): IO[PlaybackUrlResponse] =
        IO.raiseError(new AssertionError("Unexpected storage call"))

    for
      context <- Resource.eval(IOLocalRequestContext.create[UUID])
      app     <- SimpleRestJsonBuilder
               .routes(new PlaybackServiceImpl(repository, storage, context))
               .middleware(new BearerAuthenticationMiddleware(verifier, context))
               .resource
    yield app.orNotFound

  private def request(method: Method, path: String, authenticated: Boolean = true): Request[IO] =
    val request = Request[IO](method, Uri.unsafeFromString(path))
    if authenticated then
      request.putHeaders(Header.Raw(CIString("Authorization"), "Bearer valid-token"))
    else request

  private def progressRequest(position: Int, authenticated: Boolean = true): Request[IO] =
    request(Method.PUT, progressPath, authenticated)
      .withEntity(s"""{"positionSeconds":$position}""")
      .putHeaders(`Content-Type`(MediaType.application.json))

  private def valid[A](either: Either[String, A]): A =
    either.fold(message => throw new AssertionError(message), identity)
