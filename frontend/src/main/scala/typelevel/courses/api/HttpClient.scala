package typelevel.courses.api

import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import io.circe.parser.parse
import io.circe.{Decoder, Json}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.dom.FetchClientBuilder
import org.http4s.{DecodeFailure, Request, Response}

final case class ApiRequestError(
    override val getMessage: String,
    status: Int,
    code: Option[String] = None,
) extends RuntimeException(getMessage)

object HttpClient:
  private val requestTimeout = 30.seconds

  def resource: Resource[IO, Client[IO]] =
    FetchClientBuilder[IO]
      .withRequestTimeout(requestTimeout)
      .withoutStreamingRequests
      .resource

  def json[A: Decoder](client: Client[IO], request: Request[IO]): IO[A] =
    withResponse(client, request) { response =>
      if response.status.isSuccess then
        response.as[A].handleErrorWith {
          case _: DecodeFailure =>
            IO.raiseError(
              ApiRequestError("The API returned an invalid JSON response.", response.status.code),
            )
          case _ => IO.raiseError(unreadableResponse(response))
        }
      else failure(response)
    }

  def empty(client: Client[IO], request: Request[IO]): IO[Unit] =
    withResponse(client, request) { response =>
      if response.status.isSuccess then IO.unit
      else failure(response)
    }

  private def withResponse[A](client: Client[IO], request: Request[IO])(
      consume: Response[IO] => IO[A],
  ): IO[A] =
    client
      .run(request)
      .handleErrorWith(_ =>
        Resource.eval(
          IO.raiseError[Response[IO]](
            ApiRequestError("The API could not be reached.", status = 0),
          ),
        ),
      )
      .use(consume)
      // Bound body consumption as well as waiting for response headers.
      .timeoutTo(
        requestTimeout,
        IO.raiseError(ApiRequestError("The API request timed out.", status = 0)),
      )

  private def failure[A](response: Response[IO]): IO[A] =
    response.bodyText.compile.string
      .handleErrorWith(_ => IO.raiseError(unreadableResponse(response)))
      .flatMap(body => IO.raiseError(apiError(response.status.code, body)))

  private def unreadableResponse(response: Response[IO]): ApiRequestError =
    ApiRequestError("The API returned an unreadable response.", response.status.code)

  private def apiError(status: Int, body: String): ApiRequestError =
    val json    = parse(body).toOption
    val code    = stringField(json, "code")
    val message = code
      .orElse(stringField(json, "message"))
      .getOrElse(s"The API returned HTTP $status.")
    ApiRequestError(message, status, code)

  private def stringField(json: Option[Json], name: String): Option[String] =
    json.flatMap(_.hcursor.get[String](name).toOption)
