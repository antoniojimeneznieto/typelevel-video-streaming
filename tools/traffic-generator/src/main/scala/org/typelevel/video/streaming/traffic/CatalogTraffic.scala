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
    requestOperation(client, gateway, Operation.Courses, None)

  private[traffic] def soakOperation(slot: Long): Operation =
    if slot % 10 == 0 then Operation.LearningPaths
    else Operation.Courses

  def soakRequest(client: Client[IO], gateway: Uri, slot: Long): IO[RequestResult] =
    val operation = soakOperation(slot)
    val query     =
      if operation == Operation.LearningPaths then None
      else
        (slot % 50 match
          case 49 => Some("Kubernetes")
          case n if n % 5 == 1 => Some("Scala")
          case n if n % 5 == 2 => Some("Cats Effect")
          case _ => None
        ).map(value => SearchQuery(value).toOption.get)
    requestOperation(client, gateway, operation, query)

  private[traffic] def requestOperation(
      client: Client[IO],
      gateway: Uri,
      operation: Operation,
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
        if operation == Operation.LearningPaths then service.listLearningPaths().void
        else service.listCourses(limit = PageLimit(10).toOption.get, query = query).void
      }

      for
        result <- call.attempt
        code   <- status.get
        outcome = result match
                    case Right(_) => Outcome.Success
                    case Left(_) if code.exists(_ >= 400) => Outcome.HttpError
                    case Left(_: IOException) => Outcome.TransportError
                    case Left(_) if code.exists(c => c >= 200 && c < 300) => Outcome.DecodeError
                    case Left(_) => Outcome.RequestError
      yield RequestResult(code, outcome, operation)
    }
