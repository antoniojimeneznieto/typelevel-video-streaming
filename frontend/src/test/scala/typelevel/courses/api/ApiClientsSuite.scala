package typelevel.courses.api

import cats.effect.{IO, Ref, Resource}
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.dom.FetchOptions
import org.http4s.implicits.*
import org.http4s.{Header, Method, Request, Response, Status}
import org.typelevel.ci.CIString
import org.typelevel.video.streaming.backend.catalog.api.ListCoursesInput
import org.typelevel.video.streaming.backend.catalog.domain.{
  CourseLevel,
  PageLimit,
  SearchQuery,
  Topic,
}
import org.typelevel.video.streaming.backend.identity.api.RegisterInput
import org.typelevel.video.streaming.backend.identity.domain.{DisplayName, Email, NewPassword}
import smithy4s.http.RawErrorResponse

final class ApiClientsSuite extends CatsEffectSuite:
  private val courseId  = "00000000-0000-0000-0000-000000000104"
  private val emptyPage = """{"items":[],"total":0,"limit":20,"offset":0}"""
  private val userJson  =
    """{"id":"00000000-0000-0000-0000-000000000001","email":"alice@example.com","displayName":"Alice","role":"student"}"""

  test("catalog sends filters to the configured API path") {
    for
      http <- recordingClient(emptyPage)
      _    <- CatalogApi(uri"/api/catalog", http.client).listCourses(
             ListCoursesInput(
               query = Some(SearchQuery.unsafeApply("effect systems")),
               level = Some(CourseLevel.INTERMEDIATE),
               topic = Some(Topic.unsafeApply("Effects & Concurrency")),
               limit = PageLimit.unsafeApply(10),
             ),
           )
      requests <- http.requests.get
    yield
      val request = requests.head.request
      assertEquals(request.method, Method.GET)
      assertEquals(request.uri.path.renderString, "/api/catalog/courses")
      assertEquals(
        request.uri.query.params,
        Map(
          "q" -> "effect systems",
          "level" -> "intermediate",
          "topic" -> "Effects & Concurrency",
          "limit" -> "10",
        ),
      )
  }

  test("identity sends registration JSON and uses each call's bearer token") {
    val input = RegisterInput(
      Email.unsafeApply("alice@example.com"),
      NewPassword.unsafeApply("workshop-secret-123"),
      DisplayName.unsafeApply("Alice"),
    )
    for
      registerHttp <- recordingClient(userJson, Status.Created)
      currentHttp  <- recordingClient(userJson)
      _            <- IdentityApi(uri"/api/identity", registerHttp.client).register(input)
      api           = IdentityApi(uri"/api/identity", currentHttp.client)
      _            <- api.currentUser("first-token")
      _            <- api.currentUser("second-token")
      registered   <- registerHttp.requests.get
      current      <- currentHttp.requests.get
    yield
      val register = registered.head
      assertEquals(register.request.method, Method.POST)
      assertEquals(register.request.uri.path.renderString, "/api/identity/users")
      assertEquals(header(register.request, "Content-Type"), Some("application/json"))
      assertEquals(header(register.request, "Authorization"), None)
      assertEquals(
        parse(register.body),
        parse(
          """{"email":"alice@example.com","password":"workshop-secret-123","displayName":"Alice"}""",
        ),
      )
      assertEquals(current.head.request.uri.path.renderString, "/api/identity/users/me")
      assertEquals(header(current.head.request, "Authorization"), Some("Bearer first-token"))
      assertEquals(header(current(1).request, "Authorization"), Some("Bearer second-token"))
  }

  test("playback requests the lesson URL and preserves its signature") {
    val signedUrl = "http://localhost:9000/video.mp4?signature=a%2Bb"
    for
      http   <- recordingClient(s"""{"url":"$signedUrl","expiresIn":900}""")
      result <- PlaybackApi(uri"/api/playback", http.client)
                  .playbackUrl("token", courseId, "lesson-1")
      requests <- http.requests.get
    yield
      assertEquals(result.url.value, signedUrl)
      assertEquals(
        requests.head.request.uri.path.renderString,
        s"/api/playback/courses/$courseId/lessons/lesson-1/playback",
      )
      assertEquals(header(requests.head.request, "Authorization"), Some("Bearer token"))
  }

  test("playback progress sends its position with bearer authentication and keepalive") {
    val progressJson =
      s"""{"courseId":"$courseId","lessonId":"lesson-1","positionSeconds":120,"completed":false,"updatedAt":"2026-09-08T00:00:00Z"}"""
    for
      http <- recordingClient(progressJson)
      _    <- PlaybackApi(uri"/api/playback", http.client)
             .putProgress("token", courseId, "lesson-1", 120, keepalive = true)
      requests <- http.requests.get
    yield
      val progress = requests.head
      assertEquals(progress.request.method, Method.PUT)
      assertEquals(
        progress.request.uri.path.renderString,
        s"/api/playback/courses/$courseId/lessons/lesson-1/progress",
      )
      assertEquals(header(progress.request, "Authorization"), Some("Bearer token"))
      assertEquals(
        progress.request.attributes.lookup(FetchOptions.Key).flatMap(_.keepAlive),
        Some(true),
      )
      assertEquals(parse(progress.body), parse("""{"positionSeconds":120}"""))
  }

  test("playback retries a 503 and returns the next successful response") {
    for
      calls <- Ref.of[IO, Int](0)
      client = Client[IO](_ =>
                 Resource.eval(calls.getAndUpdate(_ + 1).map { count =>
                   if count == 0 then
                     jsonResponse("""{"message":"Try again"}""", Status.ServiceUnavailable)
                   else jsonResponse(emptyPage, Status.Ok)
                 }),
               )
      result <- PlaybackApi.withRetry(PlaybackApi(uri"/api/playback", client).listProgress("token"))
      count  <- calls.get
    yield
      assertEquals(result.total.value, 0L)
      assertEquals(count, 2)
  }

  test("playback does not retry an unauthorized response") {
    for
      http   <- recordingClient("", Status.Unauthorized)
      result <- PlaybackApi
                  .withRetry(PlaybackApi(uri"/api/playback", http.client).listProgress("token"))
                  .attempt
      requests <- http.requests.get
    yield
      result match
        case Left(error: RawErrorResponse) => assertEquals(error.code, 401)
        case other => fail(s"Expected HTTP 401, received $other")
      assertEquals(requests.size, 1)
  }

  final private case class RecordedRequest(request: Request[IO], body: String)
  final private case class RecordingClient(
      client: Client[IO],
      requests: Ref[IO, Vector[RecordedRequest]],
  )

  private def recordingClient(body: String, status: Status = Status.Ok): IO[RecordingClient] =
    Ref.of[IO, Vector[RecordedRequest]](Vector.empty).map { requests =>
      val client = Client[IO] { request =>
        Resource.eval(
          request.bodyText.compile.string
            .flatMap(body => requests.update(_ :+ RecordedRequest(request, body)))
            .as(jsonResponse(body, status)),
        )
      }
      RecordingClient(client, requests)
    }

  private def jsonResponse(body: String, status: Status): Response[IO] =
    Response[IO](status)
      .withEntity(body)
      .putHeaders(Header.Raw(CIString("Content-Type"), "application/json"))

  private def header(request: Request[IO], name: String): Option[String] =
    request.headers.headers.find(_.name == CIString(name)).map(_.value)
