package org.typelevel.video.streaming.traffic

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import org.http4s.client.Client
import org.http4s.implicits.uri
import org.http4s.{Method, Request, Response, Status}
import weaver.SimpleIOSuite

object CatalogTrafficSuite extends SimpleIOSuite:
  test(
    "typed catalog requests use the gateway prefix and bounded parameters, consuming the response",
  ) {
    for
      seen     <- Ref.of[IO, Option[Request[IO]]](None)
      released <- Ref.of[IO, Boolean](false)
      client    = Client[IO](req =>
                 Resource.make(
                   seen
                     .set(Some(req))
                     .as(
                       Response[IO](Status.Ok)
                         .withEntity("""{"items":[],"total":0,"limit":10,"offset":0}"""),
                     ),
                 )(_ => released.set(true)),
               )
      result  <- CatalogTraffic.request(client, uri"http://gateway.test")
      request <- seen.get
      closed  <- released.get
    yield expect.all(
      result == RequestResult(Some(200), "success"),
      request.exists(_.method == Method.GET),
      request.exists(_.uri.path.renderString == "/api/catalog/courses"),
      request.exists(_.uri.query.params.get("limit").contains("10")),
      closed,
    )
  }

  test("gateway HTTP failures retain their status without retrying") {
    for
      calls <- Ref.of[IO, Int](0)
      client = Client[IO](_ =>
                 Resource.eval(
                   calls
                     .update(_ + 1)
                     .as(Response[IO](Status.ServiceUnavailable).withEntity("upstream unavailable")),
                 ),
               )
      result <- CatalogTraffic.request(client, uri"http://gateway.test")
      count  <- calls.get
    yield expect.all(result == RequestResult(Some(503), "http_error"), count == 1)
  }

  test("soak profile reports course searches under one operation and independent path reads") {
    val operations = (0L until 100L).map(CatalogTraffic.soakOperation)
    IO.pure(
      expect.all(
        operations.count(_ == "catalog-learning-paths") == 10,
        operations.count(_ == "catalog-courses") == 90,
      ),
    )
  }

  test("soak searches mix matching and unmatched filters under the course operation") {
    for
      seen  <- Ref.of[IO, Vector[Request[IO]]](Vector.empty)
      client = Client[IO](req =>
                 Resource.eval(
                   seen
                     .update(_ :+ req)
                     .as(
                       Response[IO](Status.Ok)
                         .withEntity("""{"items":[],"total":0,"limit":10,"offset":0}"""),
                     ),
                 ),
               )
      matching <- CatalogTraffic.soakRequest(client, uri"http://gateway.test", 1L)
      missing  <- CatalogTraffic.soakRequest(client, uri"http://gateway.test", 49L)
      requests <- seen.get
    yield expect.all(
      matching == RequestResult(Some(200), "success", "catalog-courses"),
      missing == RequestResult(Some(200), "success", "catalog-courses"),
      requests.map(_.uri.query.params.get("q")) == Vector(Some("Scala"), Some("Kubernetes")),
    )
  }

  test("a malformed successful response fails typed decoding") {
    val client = Client[IO](_ => Resource.pure(Response[IO](Status.Ok).withEntity("{}")))
    CatalogTraffic.request(client, uri"http://gateway.test").map { result =>
      expect(result == RequestResult(Some(200), "decode_error"))
    }
  }

  test("invalid configuration is rejected before starting traffic") {
    val invalid = List(
      List("--rate", "0"),
      List("--rate", "10001"),
      List("--duration", "0s"),
      List("--max-concurrent", "0"),
      List("--request-timeout", "infinite"),
      List("--base-url", "http://host/api/catalog"),
      List("--base-url", "http://user:secret@host"),
      List("--unknown", "value"),
      List("--rate"),
    )
    IO.pure(
      expect.all(
        invalid.filter(Cli.command.parse(_).isRight).isEmpty,
        Cli.command.parse(List("--duration", "infinite")).exists(_.duration.isEmpty),
      ),
    )
  }
