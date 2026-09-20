package org.typelevel.video.streaming.backend.catalog

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import org.http4s.implicits.uri
import org.http4s.{HttpApp, MediaType, Method, Request, Status}
import org.typelevel.video.streaming.backend.catalog.api.CatalogService
import org.typelevel.video.streaming.backend.catalog.domain.*
import smithy4s.http4s.SimpleRestJsonBuilder
import weaver.SimpleIOSuite

object CatalogRoutesSuite extends SimpleIOSuite with CatalogFixture:

  ///////////////////////////////////////////////////////////////////////////////
  // preparation
  ///////////////////////////////////////////////////////////////////////////////

  private val service: CatalogService[IO] = new CatalogService[IO]:
    override def listCourses(
        limit: PageLimit,
        offset: PageOffset,
        query: Option[SearchQuery],
        level: Option[CourseLevel],
        kind: Option[CourseKind],
        topic: Option[Topic],
        technology: Option[Technology],
    ): IO[CoursePage] =
      if CourseFilter(limit, offset, query, level, kind, topic, technology) == courseFilter then
        IO.pure(filteredCoursePage)
      else
        IO.pure(
          coursePage.copy(
            items  = coursePage.items.drop(offset.value).take(limit.value),
            limit  = limit,
            offset = offset,
          ),
        )

    override def listLearningPaths(
        limit: PageLimit,
        offset: PageOffset,
        query: Option[SearchQuery],
        level: Option[CourseLevel],
        tone: Option[LearningPathTone],
    ): IO[LearningPathPage] =
      if LearningPathFilter(limit, offset, query, level, tone) == learningPathFilter then
        IO.pure(filteredLearningPathPage)
      else
        IO.pure(
          learningPathPage.copy(
            items  = learningPathPage.items.drop(offset.value).take(limit.value),
            limit  = limit,
            offset = offset,
          ),
        )

  private val routes: Resource[IO, HttpApp[IO]] =
    SimpleRestJsonBuilder.routes(service).resource.map(_.orNotFound)

  ///////////////////////////////////////////////////////////////////////////////
  // tests
  ///////////////////////////////////////////////////////////////////////////////

  test("GET /courses is public and supports default and explicit pagination") {
    routes.use { app =>
      for
        response <- app(Request[IO](method = Method.GET, uri = uri"/courses"))
        actual   <- response.as[CoursePage]
        paged    <- app(Request[IO](method = Method.GET, uri = uri"/courses?limit=1&offset=1"))
        page     <- paged.as[CoursePage]
      yield expect.all(
        response.status == Status.Ok,
        response.contentType.map(_.mediaType).contains(MediaType.application.json),
        actual == coursePage,
        paged.status == Status.Ok,
        page == coursePage.copy(
          items  = List(fs2),
          limit  = valid(PageLimit(1)),
          offset = valid(PageOffset(1)),
        ),
      )
    }
  }

  test("GET /courses decodes search, level, kind, topic, and technology filters") {
    routes.use { app =>
      for
        response <- app(
                      Request[IO](
                        method = Method.GET,
                        uri    =
                          uri"/courses?q=FIBERS&level=intermediate&kind=talk&topic=effects%20%26%20concurrency&technology=cats%20effect&limit=1&offset=0",
                      ),
                    )
        actual <- response.as[CoursePage]
      yield expect.all(
        response.status == Status.Ok,
        actual == filteredCoursePage,
      )
    }
  }

  test("GET /courses rejects invalid pagination with 400") {
    val invalidUris = List(
      uri"/courses?limit=0",
      uri"/courses?limit=101",
      uri"/courses?limit=abc",
      uri"/courses?offset=-1",
    )

    routes.use { app =>
      invalidUris
        .traverse { uri =>
          app(Request[IO](method = Method.GET, uri = uri)).map(_.status)
        }
        .map(statuses => expect(statuses.forall(_ == Status.BadRequest)))
    }
  }

  test("GET /learning-paths is public and supports default and explicit pagination") {
    routes.use { app =>
      for
        response <- app(Request[IO](method = Method.GET, uri = uri"/learning-paths"))
        actual   <- response.as[LearningPathPage]
        paged <- app(Request[IO](method = Method.GET, uri = uri"/learning-paths?limit=1&offset=1"))
        page  <- paged.as[LearningPathPage]
      yield expect.all(
        response.status == Status.Ok,
        response.contentType.map(_.mediaType).contains(MediaType.application.json),
        actual == learningPathPage,
        paged.status == Status.Ok,
        page == learningPathPage.copy(
          items  = List(inside),
          limit  = valid(PageLimit(1)),
          offset = valid(PageOffset(1)),
        ),
      )
    }
  }

  test("GET /learning-paths decodes search, level, and tone filters") {
    routes.use { app =>
      for
        response <-
          app(
            Request[IO](
              method = Method.GET,
              uri    = uri"/learning-paths?q=DISCOVER&level=beginner&tone=yellow&limit=1&offset=0",
            ),
          )
        actual <- response.as[LearningPathPage]
      yield expect.all(
        response.status == Status.Ok,
        actual == filteredLearningPathPage,
      )
    }
  }

  test("GET /learning-paths rejects invalid pagination with 400") {
    val invalidUris = List(
      uri"/learning-paths?limit=0",
      uri"/learning-paths?limit=101",
      uri"/learning-paths?limit=abc",
      uri"/learning-paths?offset=-1",
    )

    routes.use { app =>
      invalidUris
        .traverse { uri =>
          app(Request[IO](method = Method.GET, uri = uri)).map(_.status)
        }
        .map(statuses => expect(statuses.forall(_ == Status.BadRequest)))
    }
  }
