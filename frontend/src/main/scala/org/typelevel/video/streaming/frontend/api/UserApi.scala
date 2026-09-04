package org.typelevel.video.streaming.frontend.api

import cats.effect.IO
import io.circe.Decoder
import io.circe.parser.decode
import org.http4s.client.Client
import org.http4s.{Header, Method, Request, Uri}
import org.typelevel.ci.CIStringSyntax

import scala.util.control.NoStackTrace

final case class Profile(
    id: String,
    email: Option[String],
    username: String,
    createdAt: String,
) derives Decoder

enum ProfileState {
  case Idle
  case Loading
  case Loaded(profile: Profile)
  case Failed(message: String)
}

final class UserApi(baseUrl: String, client: Client[IO]) {

  private val profileUri =
    Uri.unsafeFromString(s"${baseUrl.stripSuffix("/")}/api/account/profile")

  def getProfile(accessToken: String): IO[Profile] = {
    val request =
      Request[IO](Method.GET, profileUri)
        .putHeaders(
          Header.Raw(ci"Accept", "application/json"),
          Header.Raw(ci"Authorization", s"Bearer $accessToken"),
        )

    client.run(request).use { response =>
      response.as[String].flatMap { text =>
        if response.status.code >= 200 && response.status.code < 300 then IO.fromEither(
          decode[Profile](text),
        )
        else if response.status.code == 401 then IO.raiseError(UserApi.Unauthorized)
        else IO.raiseError(new RuntimeException(errorMessage(response.status.code, text)))
      }
    }
  }

  private def errorMessage(status: Int, text: String): String = {
    val serverMessage = decode[UserApi.ErrorResponse](text).toOption
      .flatMap(_.message)
      .getOrElse(text)

    s"User service request failed ($status): $serverMessage"
  }

}

object UserApi {

  final private case class ErrorResponse(message: Option[String]) derives Decoder

  case object Unauthorized extends RuntimeException("Unauthorized") with NoStackTrace
}
