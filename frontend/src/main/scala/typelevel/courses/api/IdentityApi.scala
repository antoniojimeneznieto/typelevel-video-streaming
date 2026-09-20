package typelevel.courses.api

import cats.effect.IO
import org.http4s.{AuthScheme, Credentials, Method, Request, Uri}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.headers.Authorization

final class IdentityApi(baseUri: Uri, client: Client[IO]):

  def register(request: RegisterRequest): IO[User] =
    HttpClient.json[User](
      client,
      Request[IO](Method.POST, baseUri / "users").withEntity(request),
    )

  def login(request: LoginRequest): IO[LoginResponse] =
    HttpClient.json[LoginResponse](
      client,
      Request[IO](Method.POST, baseUri / "auth" / "login").withEntity(request),
    )

  def currentUser(accessToken: String): IO[User] =
    HttpClient.json[User](
      client,
      Request[IO](uri = baseUri / "users" / "me")
        .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, accessToken))),
    )
