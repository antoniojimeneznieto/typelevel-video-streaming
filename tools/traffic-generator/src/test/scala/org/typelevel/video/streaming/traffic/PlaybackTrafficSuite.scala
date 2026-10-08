package org.typelevel.video.streaming.traffic

import scala.concurrent.duration.*

import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import org.http4s.client.Client
import org.http4s.implicits.uri
import org.http4s.{Method, Response, Status}
import weaver.SimpleIOSuite

object PlaybackTrafficSuite extends SimpleIOSuite:
  test("the same Playback actors renew tokens before expiry") {
    TestControl.executeEmbed(
      for
        logins <- Ref.of[IO, Int](0)
        client  = Client[IO](request =>
                   Resource.eval {
                     request.method match
                       case Method.POST =>
                         logins.updateAndGet(_ + 1).map { count =>
                           Response[IO](Status.Ok)
                             .withEntity(s"""{"accessToken":"token-$count","expiresIn":600}""")
                         }
                       case Method.GET => IO.pure(Response[IO](Status.Ok))
                       case _ => IO.pure(Response[IO](Status.NotFound))
                   },
                 )
        request       <- PlaybackTraffic.prepare(client, uri"http://gateway.test", 20)
        prepared      <- logins.get
        first         <- request(0)
        second        <- request(1)
        beforeRenewal <- logins.get
        afterRenewal  <- request.maintenance.background.use { _ =>
                          for
                            _     <- IO.sleep(9.minutes)
                            _     <- (0L until 100L).toList.parTraverse_(request.apply)
                            count <- logins.get
                          yield count
                        }
      yield expect.all(
        prepared == 10,
        beforeRenewal == 10,
        first == RequestResult(Some(200), Outcome.Success, Operation.Favorites),
        second == RequestResult(Some(200), Outcome.Success, Operation.Favorites),
        afterRenewal == 20,
      ),
    )
  }
