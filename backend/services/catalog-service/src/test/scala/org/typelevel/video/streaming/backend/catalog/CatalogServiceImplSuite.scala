package org.typelevel.video.streaming.backend.catalog.service

import cats.effect.IO
import org.typelevel.video.streaming.backend.catalog.domain.*
import org.typelevel.video.streaming.backend.catalog.repository.CatalogRepository
import weaver.SimpleIOSuite

object CatalogServiceImplSuite extends SimpleIOSuite:

  private val repository = new CatalogRepository:
    override def listCourses(filter: CourseFilter): IO[CoursePage] =
      IO.pure(CoursePage(Nil, total(8), filter.limit, filter.offset))

    override def listLearningPaths(filter: LearningPathFilter): IO[LearningPathPage] =
      IO.pure(LearningPathPage(Nil, total(3), filter.limit, filter.offset))

  private val service = new CatalogServiceImpl(repository)

  test("list courses uses the default page") {
    service.listCourses().map { output =>
      expect.all(
        output.items.isEmpty,
        TotalCount.value(output.total) == 8,
        PageLimit.value(output.limit) == 20,
        PageOffset.value(output.offset) == 0,
      )
    }
  }

  test("list learning paths uses the requested page") {
    service
      .listLearningPaths(limit = pageLimit(2), offset = pageOffset(1))
      .map { output =>
        expect.all(
          output.items.isEmpty,
          TotalCount.value(output.total) == 3,
          PageLimit.value(output.limit) == 2,
          PageOffset.value(output.offset) == 1,
        )
      }
  }

  private def pageLimit(value: Int): PageLimit =
    PageLimit(value).fold(error => throw new IllegalArgumentException(error), identity)

  private def pageOffset(value: Int): PageOffset =
    PageOffset(value).fold(error => throw new IllegalArgumentException(error), identity)

  private def total(value: Long): TotalCount =
    TotalCount(value).fold(error => throw new IllegalArgumentException(error), identity)
