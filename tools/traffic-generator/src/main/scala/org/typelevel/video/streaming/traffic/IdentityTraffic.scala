package org.typelevel.video.streaming.traffic

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.parse
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.{AuthScheme, Credentials, MediaType, Method, Request, Uri}
import org.http4s.headers.Authorization

/** A stable synthetic actor. No token, email, or password is emitted in telemetry. */
object IdentityTraffic:
  private val email        = "lab-identity-actor-v3@example.invalid"
  private val password     = "lab-identity-password-2026"
  private val registerBody =
    s"""{"email":"$email","password":"$password","displayName":"Lab Actor"}"""
  private val loginBody = s"""{"email":"$email","password":"$password"}"""

  def prepare(client: Client[IO], gateway: Uri, loginPercent: Int): IO[Long => IO[RequestResult]] =
    val base     = gateway / "api" / "identity"
    val register = Request[IO](Method.POST, base / "users")
      .withEntity(registerBody)
      .putHeaders(`Content-Type`(MediaType.application.json))
    val login = Request[IO](Method.POST, base / "auth" / "login")
      .withEntity(loginBody)
      .putHeaders(`Content-Type`(MediaType.application.json))

    for
      registered <- client.run(register).use(r => IO.pure(r.status.code))
      _          <- IO.raiseUnless(registered == 201 || registered == 409)(
             new IllegalStateException(s"Identity actor registration returned $registered"),
           )
      token <-
        client.run(login).use { response =>
          response.as[String].flatMap { body =>
            IO.fromEither(
              parse(body).leftMap(e => new IllegalStateException(e.message)).flatMap { json =>
                json.hcursor
                  .get[String]("accessToken")
                  .leftMap(e => new IllegalStateException(e.message))
              },
            ).flatMap { value =>
              IO.raiseUnless(response.status.code == 200)(
                new IllegalStateException(s"Identity actor login returned ${response.status.code}"),
              ).as(value)
            }
          }
        }
      currentUser = Request[IO](Method.GET, base / "users" / "me")
                      .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
    yield (slot: Long) =>
      val request = if slot % 100 < loginPercent then login else currentUser
      client.run(request).use { response =>
        response.body.compile.drain.as {
          val code = response.status.code
          RequestResult(
            Some(code),
            if code >= 200 && code < 300 then "success" else "http_error",
            if slot % 100 < loginPercent then "identity-login" else "identity-current-user",
          )
        }
      }
