package org.typelevel.video.streaming.traffic

import java.io.IOException

import cats.effect.{IO, Ref}
import org.http4s.client.Client
import org.http4s.Uri
import org.typelevel.video.streaming.backend.catalog.api.CatalogService
import org.typelevel.video.streaming.backend.catalog.domain.PageLimit
import smithy4s.http4s.SimpleRestJsonBuilder

object CatalogTraffic:
  def request(client: Client[IO], gateway: Uri): IO[RequestResult] =
    Ref.of[IO, Option[Int]](None).flatMap { status =>
      val observed =
        Client[IO](req => client.run(req).evalTap(r => status.set(Some(r.status.code))))
      val call = SimpleRestJsonBuilder(CatalogService)
        .client(observed)
        .uri(gateway / "api" / "catalog")
        .resource
        .use(_.listCourses(limit = PageLimit(10).toOption.get).void)

      call.attempt.flatMap { result =>
        status.get.map { code =>
          val outcome = result match
            case Right(_) => "success"
            case Left(_) if code.exists(_ >= 400) => "http_error"
            case Left(_: IOException) => "transport_error"
            case Left(_) if code.exists(c => c >= 200 && c < 300) => "decode_error"
            case Left(_) => "request_error"
          RequestResult(code, outcome)
        }
      }
    }
