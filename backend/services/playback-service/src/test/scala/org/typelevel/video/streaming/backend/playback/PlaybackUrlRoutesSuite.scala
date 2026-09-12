package org.typelevel.video.streaming.backend.playback

import java.util.UUID

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import org.http4s.{Header, HttpApp, Method, Request, Status, Uri}
import org.typelevel.ci.CIString
import org.typelevel.video.streaming.backend.playback.api.{
  PlaybackUnavailableError,
  PlaybackUrlResponse,
  VideoNotFoundError
}
import org.typelevel.video.streaming.backend.playback.domain.{
  CourseId,
  DurationSeconds,
  ExpiresInSeconds,
  Lesson,
  LessonId,
  LessonTitle,
  ObjectKey,
  PlaybackUrl
}
import org.typelevel.video.streaming.backend.playback.repository.PlaybackRepository
import org.typelevel.video.streaming.backend.playback.service.PlaybackServiceImpl
import org.typelevel.video.streaming.backend.playback.storage.S3VideoStorage
import org.typelevel.video.streaming.backend.runtime.auth.{
  BearerAuthenticationMiddleware,
  BearerTokenVerifier
}
import org.typelevel.video.streaming.backend.runtime.context.IOLocalRequestContext
import smithy4s.Blob
import smithy4s.http4s.SimpleRestJsonBuilder
import smithy4s.json.Json
import weaver.SimpleIOSuite

object PlaybackUrlRoutesSuite extends SimpleIOSuite:

  private val userId    = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
  private val courseId  = CourseId(UUID.fromString("00000000-0000-0000-0000-000000000104"))
  private val lessonId  = valid(LessonId("lesson-1"))
  private val objectKey = valid(ObjectKey("published/alternate-cut.mp4"))
  private val lesson    = Lesson(
    courseId,
    lessonId,
    valid(LessonTitle("Threads at Scale")),
    valid(DurationSeconds(1849)),
    true,
    objectKey
  )
  private val playback = PlaybackUrlResponse(
    valid(PlaybackUrl("https://videos.example.test/course/lesson.mp4?signature=test-only")),
    valid(ExpiresInSeconds(900))
  )

  test("missing or invalid bearer credentials return 401 without calling storage") {
    for
      context <- IOLocalRequestContext.create[UUID]
      calls   <- Ref.of[IO, Int](0)
      storage  = new S3VideoStorage:
                  override def getPlaybackUrl(objectKey: ObjectKey): IO[PlaybackUrlResponse] =
                    calls.update(_ + 1).as(playback)
      result <- routes(storage, context, new PlaybackRepositoryStub {}).use { app =>
                  for
                    responses <- List(None, Some("Bearer invalid-token"), Some("Basic invalid"))
                                   .traverse(auth => app(request(auth)))
                    count     <- calls.get
                    principal <- context.get
                  yield expect.all(
                    responses.forall(_.status == Status.Unauthorized),
                    responses.forall(
                      _.headers.headers.exists(header =>
                        header.name == CIString("WWW-Authenticate") && header.value == "Bearer"
                      )
                    ),
                    count == 0,
                    principal.isEmpty
                  )
                }
    yield result
  }

  test("authenticated playback signs the projected object key and scopes the verified user") {
    for
      context <- IOLocalRequestContext.create[UUID]
      calls   <- Ref.of[IO, List[(ObjectKey, Option[UUID])]](Nil)
      storage  = new S3VideoStorage:
                  override def getPlaybackUrl(objectKey: ObjectKey): IO[PlaybackUrlResponse] =
                    context.get.flatMap(principal =>
                      calls.update(_ :+ (objectKey, principal)).as(playback)
                    )
      result <- routes(storage, context).use { app =>
                  for
                    response   <- app(request(Some("Bearer valid-token")))
                    body       <- response.as[String]
                    actual     <- calls.get
                    afterScope <- context.get
                  yield expect.all(
                    response.status == Status.Ok,
                    Json.read[PlaybackUrlResponse](Blob(body)) == Right(playback),
                    actual == List((objectKey, Some(userId))),
                    afterScope.isEmpty
                  )
                }
    yield result
  }

  test("missing projections return modeled errors without reaching storage") {
    val missingUser = new KnownProjection:
      override def userExists(id: UUID): IO[Boolean] = IO.pure(false)

    val missingLesson = new KnownProjection:
      override def findLesson(courseId: CourseId, lessonId: LessonId): IO[Option[Lesson]] =
        IO.pure(None)

    val scenarios = List(
      (missingUser, Status.ServiceUnavailable, "User data is not available yet"),
      (missingLesson, Status.NotFound, "Video not found")
    )

    scenarios
      .traverse { case (repository, status, message) =>
        for
          context <- IOLocalRequestContext.create[UUID]
          calls   <- Ref.of[IO, Int](0)
          storage  = new S3VideoStorage:
                      override def getPlaybackUrl(objectKey: ObjectKey): IO[PlaybackUrlResponse] =
                        calls.update(_ + 1).as(playback)
          result <- routes(storage, context, repository).use { app =>
                      for
                        response   <- app(request(Some("Bearer valid-token")))
                        body       <- response.as[String]
                        count      <- calls.get
                        afterScope <- context.get
                      yield expect.all(
                        response.status == status,
                        body.contains(s"\"message\":\"$message\""),
                        count == 0,
                        afterScope.isEmpty
                      )
                    }
        yield result
      }
      .map(_.reduce(_ and _))
  }

  test("repository failures propagate without reaching storage and clear request context") {
    val failure    = new RuntimeException("private-database-error")
    val repository = new KnownProjection:
      override def findLesson(courseId: CourseId, lessonId: LessonId): IO[Option[Lesson]] =
        IO.raiseError(failure)

    for
      context <- IOLocalRequestContext.create[UUID]
      calls   <- Ref.of[IO, Int](0)
      storage  = new S3VideoStorage:
                  override def getPlaybackUrl(objectKey: ObjectKey): IO[PlaybackUrlResponse] =
                    calls.update(_ + 1).as(playback)
      result <- routes(storage, context, repository).use { app =>
                  for
                    response   <- app(request(Some("Bearer valid-token"))).attempt
                    count      <- calls.get
                    afterScope <- context.get
                  yield expect.all(
                    response.left.exists(_ eq failure),
                    count == 0,
                    afterScope.isEmpty
                  )
                }
    yield result
  }

  test("storage failures become modeled 404 or 503 responses and clear request context") {
    val failures = List(
      (VideoNotFoundError("Video not found"), Status.NotFound, "Video not found"),
      (
        PlaybackUnavailableError("Video storage is temporarily unavailable"),
        Status.ServiceUnavailable,
        "Video storage is temporarily unavailable"
      )
    )

    failures
      .traverse { case (error, status, message) =>
        for
          context <- IOLocalRequestContext.create[UUID]
          calls   <- Ref.of[IO, Int](0)
          storage  = new S3VideoStorage:
                      override def getPlaybackUrl(objectKey: ObjectKey): IO[PlaybackUrlResponse] =
                        calls.update(_ + 1) *> IO.raiseError(error)
          result <- routes(storage, context).use { app =>
                      for
                        response   <- app(request(Some("Bearer valid-token")))
                        body       <- response.as[String]
                        count      <- calls.get
                        afterScope <- context.get
                      yield expect.all(
                        response.status == status,
                        body.contains(s"\"message\":\"$message\""),
                        count == 1,
                        afterScope.isEmpty
                      )
                    }
        yield result
      }
      .map(_.reduce(_ and _))
  }

  private def routes(
      storage: S3VideoStorage,
      context: IOLocalRequestContext[UUID],
      repository: PlaybackRepository = new KnownProjection
  ): Resource[IO, HttpApp[IO]] =
    val verifier = new BearerTokenVerifier[IO, UUID]:
      override def verify(token: String): IO[Option[UUID]] =
        IO.pure(Option.when(token == "valid-token")(userId))

    SimpleRestJsonBuilder
      .routes(new PlaybackServiceImpl(repository, storage, context))
      .middleware(new BearerAuthenticationMiddleware(verifier, context))
      .resource
      .map(_.orNotFound)

  private class KnownProjection extends PlaybackRepositoryStub:
    override def userExists(id: UUID): IO[Boolean] = IO.pure(id == userId)

    override def findLesson(id: CourseId, lesson: LessonId): IO[Option[Lesson]] =
      IO.pure(Option.when(id == courseId && lesson == lessonId)(PlaybackUrlRoutesSuite.lesson))

  private def request(authorization: Option[String]): Request[IO] =
    val request = Request[IO](
      Method.GET,
      Uri.unsafeFromString(s"/courses/${courseId.value}/lessons/${lessonId.value}/playback")
    )
    authorization.fold(request)(value =>
      request.putHeaders(Header.Raw(CIString("Authorization"), value))
    )

  private def valid[A](result: Either[String, A]): A =
    result.fold(message => throw new AssertionError(message), identity)
