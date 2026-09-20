package typelevel.courses.api

import cats.effect.IO
import org.http4s.client.Client
import org.http4s.{Request, Uri}

final class CatalogApi(baseUri: Uri, client: Client[IO]):

  def listCourses(params: ListCoursesParams = ListCoursesParams()): IO[Page[ApiCourse]] =
    HttpClient.json[Page[ApiCourse]](
      client,
      Request[IO](
        uri = (baseUri / "courses")
          .withOptionQueryParam("q", params.query.filter(_.nonEmpty))
          .withOptionQueryParam("level", params.level.map(_.value))
          .withOptionQueryParam("kind", params.kind.map(_.value))
          .withOptionQueryParam("topic", params.topic.filter(_.nonEmpty))
          .withOptionQueryParam("technology", params.technology.filter(_.nonEmpty))
          .withOptionQueryParam("limit", params.limit)
          .withOptionQueryParam("offset", params.offset),
      ),
    )

  def listLearningPaths(
      params: ListLearningPathsParams = ListLearningPathsParams(),
  ): IO[Page[ApiLearningPath]] =
    HttpClient.json[Page[ApiLearningPath]](
      client,
      Request[IO](
        uri = (baseUri / "learning-paths")
          .withOptionQueryParam("q", params.query.filter(_.nonEmpty))
          .withOptionQueryParam("level", params.level.map(_.value))
          .withOptionQueryParam("tone", params.tone.map(_.value))
          .withOptionQueryParam("limit", params.limit)
          .withOptionQueryParam("offset", params.offset),
      ),
    )
