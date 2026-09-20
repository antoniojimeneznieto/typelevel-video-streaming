package typelevel.courses.api

import scala.concurrent.duration.*

import cats.effect.{IO, Temporal}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.dom.FetchOptions
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Method, Request, Uri}

final class PlaybackApi(baseUri: Uri, client: Client[IO]):

  def playbackUrl(
      accessToken: String,
      courseId: String,
      lessonId: String,
  ): IO[PlaybackUrlResponse] =
    HttpClient.json[PlaybackUrlResponse](
      client,
      Request[IO](uri = lessonUri(courseId, lessonId) / "playback")
        .putHeaders(bearer(accessToken)),
    )

  def putProgress(
      accessToken: String,
      courseId: String,
      lessonId: String,
      positionSeconds: Int,
      keepalive: Boolean = false,
  ): IO[PlaybackProgress] =
    HttpClient.json[PlaybackProgress](
      client,
      Request[IO](
        Method.PUT,
        lessonUri(courseId, lessonId) / "progress",
      )
        .putHeaders(bearer(accessToken))
        .withEntity(PositionRequest(positionSeconds))
        .withAttribute(FetchOptions.Key, FetchOptions.default.withKeepAlive(keepalive)),
    )

  def listProgress(
      accessToken: String,
      query: ProgressQuery = ProgressQuery(),
  ): IO[Page[PlaybackProgress]] =
    HttpClient.json[Page[PlaybackProgress]](
      client,
      Request[IO](
        uri = (baseUri / "progress")
          .withOptionQueryParam("courseId", query.courseId.filter(_.nonEmpty))
          .withOptionQueryParam("completed", query.completed)
          .withOptionQueryParam("limit", query.limit)
          .withOptionQueryParam("offset", query.offset),
      ).putHeaders(bearer(accessToken)),
    )

  def putFavorite(accessToken: String, courseId: String): IO[Favorite] =
    HttpClient.json[Favorite](
      client,
      Request[IO](
        Method.PUT,
        baseUri / "favorites" / courseId,
      )
        .putHeaders(bearer(accessToken)),
    )

  def deleteFavorite(accessToken: String, courseId: String): IO[Unit] =
    HttpClient.empty(
      client,
      Request[IO](
        Method.DELETE,
        baseUri / "favorites" / courseId,
      )
        .putHeaders(bearer(accessToken)),
    )

  def listFavorites(
      accessToken: String,
      query: FavoritesQuery = FavoritesQuery(),
  ): IO[Page[Favorite]] =
    HttpClient.json[Page[Favorite]](
      client,
      Request[IO](
        uri = (baseUri / "favorites")
          .withOptionQueryParam("limit", query.limit)
          .withOptionQueryParam("offset", query.offset),
      ).putHeaders(bearer(accessToken)),
    )

  private def lessonUri(courseId: String, lessonId: String): Uri =
    baseUri / "courses" / courseId / "lessons" / lessonId

  private def bearer(accessToken: String): Authorization =
    Authorization(Credentials.Token(AuthScheme.Bearer, accessToken))

object PlaybackApi:
  private val retryDelays = Vector(250.millis, 750.millis, 1500.millis)

  def withRetry[A](request: IO[A]): IO[A] =
    def loop(remaining: Vector[FiniteDuration]): IO[A] =
      request.handleErrorWith {
        case error: ApiRequestError if error.status == 503 && remaining.nonEmpty =>
          Temporal[IO].sleep(remaining.head) *> loop(remaining.tail)
        case error => IO.raiseError(error)
      }

    loop(retryDelays)
