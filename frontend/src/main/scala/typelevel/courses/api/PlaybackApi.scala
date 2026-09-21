package typelevel.courses.api

import java.util.UUID
import scala.concurrent.duration.*
import scala.util.Try

import cats.effect.{IO, Temporal}
import org.http4s.client.Client
import org.http4s.Uri
import org.typelevel.video.streaming.backend.playback.api.{
  ListFavoritesInput,
  ListPlaybackProgressInput,
  PlaybackService,
  PlaybackUnavailableError,
  PlaybackUrlResponse,
}
import org.typelevel.video.streaming.backend.playback.domain.{
  CourseId,
  Favorite,
  FavoritePage,
  LessonId,
  PlaybackProgress,
  PlaybackProgressPage,
  PositionSeconds,
}
import smithy4s.http.RawErrorResponse
import smithy4s.http4s.SimpleRestJsonBuilder

final class PlaybackApi(baseUri: Uri, client: Client[IO]):
  private val smithy = SmithyClient(
    client,
    transport => SimpleRestJsonBuilder(PlaybackService).client(transport).uri(baseUri).resource,
  )

  def playbackUrl(
      accessToken: String,
      courseId: String,
      lessonId: String,
  ): IO[PlaybackUrlResponse] =
    for
      course <- validCourseId(courseId)
      lesson <- validated(LessonId(lessonId))
      result <- smithy.call(Some(accessToken))(_.getPlaybackUrl(course, lesson))
    yield result

  def putProgress(
      accessToken: String,
      courseId: String,
      lessonId: String,
      positionSeconds: Int,
      keepalive: Boolean = false,
  ): IO[PlaybackProgress] =
    for
      course   <- validCourseId(courseId)
      lesson   <- validated(LessonId(lessonId))
      position <- validated(PositionSeconds(positionSeconds))
      result   <-
        smithy.call(Some(accessToken), Some(keepalive))(
          _.updatePlaybackProgress(course, lesson, position),
        )
    yield result

  def listProgress(
      accessToken: String,
      query: ListPlaybackProgressInput = ListPlaybackProgressInput(),
  ): IO[PlaybackProgressPage] =
    smithy.call(Some(accessToken)) {
      _.listPlaybackProgress(query.limit, query.offset, query.courseId, query.completed)
    }

  def putFavorite(accessToken: String, courseId: String): IO[Favorite] =
    validCourseId(courseId).flatMap(course => smithy.call(Some(accessToken))(_.addFavorite(course)))

  def deleteFavorite(accessToken: String, courseId: String): IO[Unit] =
    validCourseId(courseId).flatMap(course =>
      smithy.call(Some(accessToken))(_.removeFavorite(course)),
    )

  def listFavorites(
      accessToken: String,
      query: ListFavoritesInput = ListFavoritesInput(),
  ): IO[FavoritePage] =
    smithy.call(Some(accessToken))(_.listFavorites(query.limit, query.offset))

  private def validCourseId(value: String): IO[CourseId] =
    validated(
      Try(UUID.fromString(value)).toEither.left.map(_ => "Invalid course ID.").map(CourseId(_)),
    )

  private def validated[A](value: Either[String, A]): IO[A] =
    IO.fromEither(value.left.map(message => new IllegalArgumentException(message)))

object PlaybackApi:
  private val retryDelays = Vector(250.millis, 750.millis, 1500.millis)

  def withRetry[A](request: IO[A]): IO[A] =
    def loop(remaining: Vector[FiniteDuration]): IO[A] =
      request.handleErrorWith {
        case _: PlaybackUnavailableError if remaining.nonEmpty =>
          Temporal[IO].sleep(remaining.head) *> loop(remaining.tail)
        case error: RawErrorResponse if error.code == 503 && remaining.nonEmpty =>
          Temporal[IO].sleep(remaining.head) *> loop(remaining.tail)
        case error => IO.raiseError(error)
      }

    loop(retryDelays)
