package org.typelevel.video.streaming.traffic

import scala.concurrent.duration.*
import cats.effect.{IO, Ref}
import io.circe.{Decoder, Json}
import io.circe.parser.decode
import org.http4s.{AuthScheme, Credentials, MediaType, Method, Request, Uri}
import org.http4s.client.Client
import org.http4s.headers.{Authorization, `Content-Type`}

/** One maintenance loop per actor owns renewal; request fibers only read the current token. */
final private[traffic] class ActorSession private (
    client: Client[IO],
    login: Request[IO],
    token: Ref[IO, ActorSession.Token],
):
  def authorize(request: Request[IO]): IO[Request[IO]] =
    token.get.map(current =>
      request.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, current.value))),
    )

  def maintain: IO[Unit] =
    (for
      current <- token.get
      now     <- IO.monotonic
      _       <- IO.sleep((current.renewAt - now).max(Duration.Zero))
      fresh   <- ActorSession.authenticate(client, login).timeout(30.seconds)
      _       <- token.set(fresh)
      _       <- IO.println(
             Json
               .obj(
                 "event" -> Json.fromString("token-renewal"),
                 "outcome" -> Json.fromString("success"),
               )
               .noSpaces,
           )
    yield ()).foreverM

private[traffic] object ActorSession:
  final private case class LoginResponse(accessToken: String, expiresIn: Long)
  private given Decoder[LoginResponse] =
    Decoder.forProduct2("accessToken", "expiresIn")(LoginResponse.apply)
  final private case class Token(value: String, renewAt: FiniteDuration)

  def jsonRequest(method: Method, uri: Uri, fields: (String, String)*): Request[IO] =
    Request[IO](method, uri)
      .withEntity(Json.obj(fields.map((key, value) => key -> Json.fromString(value))*).noSpaces)
      .putHeaders(`Content-Type`(MediaType.application.json))

  private def authenticate(client: Client[IO], request: Request[IO]): IO[Token] =
    client.run(request).use { response =>
      for
        _ <- IO.raiseUnless(response.status.code == 200)(
               new IllegalStateException(s"Actor login returned ${response.status.code}"),
             )
        body  <- response.as[String]
        login <- IO.fromEither(decode[LoginResponse](body))
        _     <- IO.raiseUnless(login.expiresIn > 0 && login.accessToken.nonEmpty)(
               new IllegalStateException("Invalid actor token lifetime or token"),
             )
        now <- IO.monotonic
      yield Token(login.accessToken, now + (login.expiresIn * 800).millis)
    }

  def prepare(client: Client[IO], login: Request[IO]): IO[ActorSession] =
    for
      initial <- authenticate(client, login)
      token   <- Ref.of[IO, Token](initial)
    yield new ActorSession(client, login, token)

  def execute(
      client: Client[IO],
      request: IO[Request[IO]],
      operation: Operation,
  ): IO[RequestResult] =
    for
      authorized <- request
      result     <- client.run(authorized).use { response =>
                  response.body.compile.drain.as(
                    RequestResult(
                      Some(response.status.code),
                      if response.status.isSuccess then Outcome.Success else Outcome.HttpError,
                      operation,
                    ),
                  )
                }
    yield result
