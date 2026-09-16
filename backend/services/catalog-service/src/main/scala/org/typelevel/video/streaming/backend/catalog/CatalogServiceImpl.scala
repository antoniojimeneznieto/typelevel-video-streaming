package org.typelevel.video.streaming.backend.catalog.service

import cats.effect.IO
import org.typelevel.video.streaming.backend.catalog.api.*
import org.typelevel.video.streaming.backend.catalog.domain.*
import org.typelevel.video.streaming.backend.catalog.repository.CatalogRepository

final class CatalogServiceImpl(
    repository: CatalogRepository,
) extends CatalogService[IO]:

  override def listCourses(
      limit: PageLimit,
      offset: PageOffset,
      query: Option[SearchQuery],
      level: Option[CourseLevel],
      kind: Option[CourseKind],
      topic: Option[Topic],
      technology: Option[Technology],
  ): IO[CoursePage] =
    repository.listCourses(
      CourseFilter(
        limit      = limit,
        offset     = offset,
        query      = query,
        level      = level,
        kind       = kind,
        topic      = topic,
        technology = technology,
      ),
    )

  override def listLearningPaths(
      limit: PageLimit,
      offset: PageOffset,
      query: Option[SearchQuery],
      level: Option[CourseLevel],
      tone: Option[LearningPathTone],
  ): IO[LearningPathPage] =
    repository.listLearningPaths(
      LearningPathFilter(
        limit  = limit,
        offset = offset,
        query  = query,
        level  = level,
        tone   = tone,
      ),
    )
