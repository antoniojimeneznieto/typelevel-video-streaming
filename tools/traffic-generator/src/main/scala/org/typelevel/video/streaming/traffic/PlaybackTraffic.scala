package org.typelevel.video.streaming.traffic

import scala.concurrent.duration.*

import cats.effect.{Clock, IO, Ref}
import cats.syntax.all.*
import io.circe.parser.parse
import org.http4s.client.Client
import org.http4s.headers.{Authorization, `Content-Type`}
import org.http4s.{AuthScheme, Credentials, MediaType, Method, Request, Uri}

/** Ten stable workshop actors. Slot routing is deterministic and never labels telemetry by user. */
object PlaybackTraffic:
  private val password = "lab-playback-password-2026"

  private def email(actor: Int): String =
    val kind = if actor < 8 then "old" else "new"
    s"lab-playback-$kind-$actor@example.invalid"

  def prepare(client: Client[IO], gateway: Uri, modernPercent: Int): IO[Long => IO[RequestResult]] =
    val identity = gateway / "api" / "identity"
    val playback = gateway / "api" / "playback" / "favorites"
    val login = (actor: Int) =>
      val address = email(actor)
      val request = Request[IO](Method.POST, identity / "auth" / "login")
          .withEntity(s"""{"email":"$address","password":"$password"}""")
          .putHeaders(`Content-Type`(MediaType.application.json))
      client
        .run(request)
        .use { response =>
          response.as[String].flatMap { body =>
            IO.raiseUnless(response.status.code == 200)(
              new IllegalStateException(s"Playback actor login returned ${response.status.code}"),
            ) *> IO.fromEither(
              parse(body).leftMap(e => new IllegalStateException(e.message)).flatMap { json =>
                json.hcursor
                  .get[String]("accessToken")
                  .leftMap(e => new IllegalStateException(e.message))
              },
            )
          }
        }
        .map { token =>
          Request[IO](Method.GET, playback)
            .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
        }
    (0 until 10).toList
      .traverse(login)
      .flatTap(_ => IO.println("""{"event":"preparation","profile":"playback","identity_login_successes":10,"legacy_subject_actors":8,"modern_subject_actors":2}"""))
      .flatMap { requests =>
        Clock[IO].monotonic.flatMap { preparedAt =>
          Ref.of[IO, Vector[(FiniteDuration, Request[IO])]](
            requests.map(preparedAt -> _).toVector,
          ).map { current => (slot: Long) =>
            val actor =
              if modernPercent == 20 && slot % 5 == 0 then 8 + ((slot / 5) % 2).toInt
              else ((slot / 2) % 8).toInt
            Clock[IO].monotonic.flatMap { now =>
              current.get.flatMap { entries =>
                if now - entries(actor)._1 >= 10.minutes then
                  login(actor).flatTap(fresh => current.update(_.updated(actor, now -> fresh)))
                else IO.pure(entries(actor)._2)
              }
            }.flatMap { request =>
              client.run(request).use { response =>
                response.body.compile.drain.as {
                  val code = response.status.code
                  RequestResult(
                    Some(code),
                    if code >= 200 && code < 300 then "success" else "http_error",
                    "playback-favorites",
                  )
                }
              }
            }
          }
        }
      }
