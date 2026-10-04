package org.typelevel.video.streaming.traffic

import java.io.IOException

import cats.effect.{IO, Ref}
import org.http4s.client.Client
import org.http4s.Uri
import org.typelevel.video.streaming.backend.catalog.api.CatalogService
import org.typelevel.video.streaming.backend.catalog.domain.PageLimit
import org.typelevel.video.streaming.backend.catalog.domain.SearchQuery
import smithy4s.http4s.SimpleRestJsonBuilder

object CatalogTraffic:
  def request(client: Client[IO], gateway: Uri): IO[RequestResult] =
    requestOperation(client, gateway, "catalog-courses", None)

  private[traffic] def soakOperation(slot: Long): String =
    if slot % 50 == 49 then "catalog-empty-search"
    else if slot % 10 == 0 then "catalog-learning-paths"
    else "catalog-courses"

  def soakRequest(client: Client[IO], gateway: Uri, slot: Long): IO[RequestResult] =
    val operation = soakOperation(slot)
    val query     = Option.when(operation == "catalog-empty-search")(
      SearchQuery("no-course-matches-lab-2026").toOption.get,
    )
    requestOperation(client, gateway, operation, query)

  private def requestOperation(
      client: Client[IO],
      gateway: Uri,
      operation: String,
      query: Option[SearchQuery],
  ): IO[RequestResult] =
    Ref.of[IO, Option[Int]](None).flatMap { status =>
      val observed =
        Client[IO](req => client.run(req).evalTap(r => status.set(Some(r.status.code))))
      val catalog = SimpleRestJsonBuilder(CatalogService)
        .client(observed)
        .uri(gateway / "api" / "catalog")
        .resource
      val call = catalog.use { service =>
        if operation == "catalog-learning-paths" then service.listLearningPaths().void
        else service.listCourses(limit = PageLimit(10).toOption.get, query = query).void
      }

      call.attempt.flatMap { result =>
        status.get.map { code =>
          val outcome = result match
            case Right(_) => "success"
            case Left(_) if code.exists(_ >= 400) => "http_error"
            case Left(_: IOException) => "transport_error"
            case Left(_) if code.exists(c => c >= 200 && c < 300) => "decode_error"
            case Left(_) => "request_error"
          RequestResult(code, outcome, operation)
        }
      }
    }
