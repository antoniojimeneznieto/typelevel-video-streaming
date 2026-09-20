package org.typelevel.video.streaming.backend.catalog.service

import cats.effect.{IO, Resource}
import org.typelevel.video.streaming.backend.catalog.CatalogFixture
import org.typelevel.video.streaming.backend.catalog.api.CatalogService
import org.typelevel.video.streaming.backend.catalog.domain.*
import org.typelevel.video.streaming.backend.catalog.repository.CatalogRepositoryImpl
import weaver.SimpleIOSuite

object CatalogServiceImplSuite extends SimpleIOSuite with CatalogFixture:

  ///////////////////////////////////////////////////////////////////////////////
  // preparation
  ///////////////////////////////////////////////////////////////////////////////

  private def serviceWithDatabase: Resource[IO, CatalogService[IO]] =
    sessionPool.map(sessions => new CatalogServiceImpl(new CatalogRepositoryImpl(sessions)))

  ///////////////////////////////////////////////////////////////////////////////
  // tests
  ///////////////////////////////////////////////////////////////////////////////

  test("list courses returns the default page with ordered technologies and optional fields") {
    serviceWithDatabase.use { service =>
      service.listCourses().map(actual => expect(actual == coursePage))
    }
  }

  test("course pagination preserves complete courses and the total beyond the last page") {
    serviceWithDatabase.use { service =>
      val limit = valid(PageLimit(1))

      for
        first  <- service.listCourses(limit = limit)
        second <- service.listCourses(limit = limit, offset = valid(PageOffset(1)))
        beyond <- service.listCourses(limit = limit, offset = valid(PageOffset(3)))
      yield expect.all(
        first == coursePage.copy(items = List(catsEffect), limit = limit),
        second == coursePage.copy(items = List(fs2), limit = limit, offset = valid(PageOffset(1))),
        beyond == coursePage.copy(items = Nil, limit = limit, offset = valid(PageOffset(3))),
      )
    }
  }

  test("course filters combine case-insensitive search and technology matching") {
    serviceWithDatabase.use { service =>
      for
        matching <- service.listCourses(
                      limit      = courseFilter.limit,
                      offset     = courseFilter.offset,
                      query      = courseFilter.query,
                      level      = courseFilter.level,
                      kind       = courseFilter.kind,
                      topic      = courseFilter.topic,
                      technology = courseFilter.technology,
                    )
        missing <- service.listCourses(query = Some(valid(SearchQuery("missing"))))
      yield expect.all(
        matching == filteredCoursePage,
        missing == coursePage.copy(items = Nil, total = valid(TotalCount(0))),
      )
    }
  }

  test("list learning paths returns ordered course membership and paths without courses") {
    serviceWithDatabase.use { service =>
      service.listLearningPaths().map(actual => expect(actual == learningPathPage))
    }
  }

  test("learning path pagination preserves membership and the total beyond the last page") {
    serviceWithDatabase.use { service =>
      val limit = valid(PageLimit(1))

      for
        first  <- service.listLearningPaths(limit = limit)
        second <- service.listLearningPaths(limit = limit, offset = valid(PageOffset(1)))
        beyond <- service.listLearningPaths(limit = limit, offset = valid(PageOffset(2)))
      yield expect.all(
        first == learningPathPage.copy(items = List(discover), limit = limit),
        second == learningPathPage
          .copy(items = List(inside), limit = limit, offset = valid(PageOffset(1))),
        beyond == learningPathPage.copy(items = Nil, limit = limit, offset = valid(PageOffset(2))),
      )
    }
  }

  test("learning path filters combine search, level, and tone") {
    serviceWithDatabase.use { service =>
      for
        matching <- service.listLearningPaths(
                      limit  = learningPathFilter.limit,
                      offset = learningPathFilter.offset,
                      query  = learningPathFilter.query,
                      level  = learningPathFilter.level,
                      tone   = learningPathFilter.tone,
                    )
        missing <- service.listLearningPaths(query = Some(valid(SearchQuery("missing"))))
      yield expect.all(
        matching == filteredLearningPathPage,
        missing == learningPathPage.copy(items = Nil, total = valid(TotalCount(0))),
      )
    }
  }
