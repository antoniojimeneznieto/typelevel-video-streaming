package typelevel.courses.api

import cats.effect.IO
import org.http4s.client.Client
import org.http4s.Uri
import org.typelevel.video.streaming.backend.catalog.api.{
  CatalogService,
  ListCoursesInput,
  ListLearningPathsInput,
}
import org.typelevel.video.streaming.backend.catalog.domain.{CoursePage, LearningPathPage}
import smithy4s.http4s.SimpleRestJsonBuilder

final class CatalogApi(baseUri: Uri, client: Client[IO]):
  private val smithy = SmithyClient(
    client,
    transport => SimpleRestJsonBuilder(CatalogService).client(transport).uri(baseUri).resource,
  )

  def listCourses(params: ListCoursesInput = ListCoursesInput()): IO[CoursePage] =
    smithy.call() {
      _.listCourses(
        limit      = params.limit,
        offset     = params.offset,
        query      = params.query,
        level      = params.level,
        kind       = params.kind,
        topic      = params.topic,
        technology = params.technology,
      )
    }

  def listLearningPaths(
      params: ListLearningPathsInput = ListLearningPathsInput(),
  ): IO[LearningPathPage] =
    smithy.call() {
      _.listLearningPaths(
        limit  = params.limit,
        offset = params.offset,
        query  = params.query,
        level  = params.level,
        tone   = params.tone,
      )
    }
