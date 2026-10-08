package org.typelevel.video.streaming.traffic

import cats.effect.{IO, Ref, Resource}
import cats.effect.testkit.TestControl
import scala.concurrent.duration.*
import cats.syntax.all.*
import org.http4s.client.Client
import org.http4s.implicits.uri
import org.http4s.{Method, Response, Status}
import weaver.SimpleIOSuite

object IdentityTrafficSuite extends SimpleIOSuite:
  test("register once and select a stable login/current-user mix by arrival slot") {
    for
      seen  <- Ref.of[IO, Vector[(Method, String)]](Vector.empty)
      client = Client[IO](request =>
                 Resource.eval {
                   seen.update(_ :+ (request.method, request.uri.path.renderString)).as {
                     request.uri.path.renderString match
                       case "/api/identity/users" => Response[IO](Status.Created)
                       case "/api/identity/auth/login" =>
                         Response[IO](Status.Ok)
                           .withEntity("""{"accessToken":"test-token","expiresIn":7200}""")
                       case "/api/identity/users/me" => Response[IO](Status.Ok)
                       case _ => Response[IO](Status.NotFound)
                   }
                 },
               )
      request <- IdentityTraffic.prepare(client, uri"http://gateway.test", 70)
      login   <- request(0)
      current <- request(70)
      calls   <- seen.get
    yield expect.all(
      login == RequestResult(Some(200), Outcome.Success, Operation.Login),
      current == RequestResult(Some(200), Outcome.Success, Operation.CurrentUser),
      calls.map(_._2) == Vector(
        "/api/identity/users",
        "/api/identity/auth/login",
        "/api/identity/auth/login",
        "/api/identity/users/me",
      ),
    )
  }

  test("current-user-only traffic renews outside requests using the issued lifetime") {
    TestControl.executeEmbed(
      for
        logins   <- Ref.of[IO, Int](0)
        observed <- Ref.of[IO, Vector[String]](Vector.empty)
        client    = Client[IO](request =>
                   Resource.eval {
                     request.uri.path.renderString match
                       case "/api/identity/users" => IO.pure(Response[IO](Status.Created))
                       case "/api/identity/auth/login" =>
                         logins.updateAndGet(_ + 1).map { count =>
                           Response[IO](Status.Ok)
                             .withEntity(s"""{"accessToken":"token-$count","expiresIn":10}""")
                         }
                       case _ =>
                         observed.update(_ :+ request.headers.toString).as(Response[IO](Status.Ok))
                   },
                 )
        workload <- IdentityTraffic.prepare(client, uri"http://gateway.test", 0)
        _        <- workload.maintenance.background.use { _ =>
               for
                 _ <- workload(0)
                 _ <- IO.sleep(9.seconds)
                 _ <- workload(1)
               yield ()
             }
        count   <- logins.get
        headers <- observed.get
      yield expect(count == 2) && expect(headers.headOption.exists(_.contains("token-1"))) &&
        expect(headers.lastOption.exists(_.contains("token-2"))),
    )
  }
