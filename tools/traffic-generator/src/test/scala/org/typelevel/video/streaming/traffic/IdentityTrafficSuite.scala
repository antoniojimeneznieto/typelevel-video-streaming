package org.typelevel.video.streaming.traffic

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import org.http4s.client.Client
import org.http4s.implicits.uri
import org.http4s.{Method, Response, Status}
import weaver.SimpleIOSuite

object IdentityTrafficSuite extends SimpleIOSuite:
  test("register once and select a stable login/current-user mix by arrival slot") {
    for
      seen <- Ref.of[IO, Vector[(Method, String)]](Vector.empty)
      client = Client[IO](request => Resource.eval {
                 seen.update(_ :+ (request.method, request.uri.path.renderString)).as {
                   request.uri.path.renderString match
                     case "/api/identity/users" => Response[IO](Status.Created)
                     case "/api/identity/auth/login" =>
                       Response[IO](Status.Ok).withEntity("""{"accessToken":"test-token"}""")
                     case "/api/identity/users/me" => Response[IO](Status.Ok)
                     case _ => Response[IO](Status.NotFound)
                 }
               })
      request <- IdentityTraffic.prepare(client, uri"http://gateway.test", 70)
      login   <- request(0)
      current <- request(70)
      calls   <- seen.get
    yield expect.all(
      login == RequestResult(Some(200), "success", "identity-login"),
      current == RequestResult(Some(200), "success", "identity-current-user"),
      calls.map(_._2) == Vector(
        "/api/identity/users",
        "/api/identity/auth/login",
        "/api/identity/auth/login",
        "/api/identity/users/me",
      ),
    )
  }
