package typelevel.courses.api

import scala.concurrent.duration.*

import cats.effect.{Deferred, IO, Ref, Resource}
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.dom.FetchOptions
import org.http4s.implicits.*
import org.http4s.{Header, Method, Request, Response, Status, Uri}
import org.typelevel.ci.CIString

final class ApiClientsSuite extends CatsEffectSuite:
  private val emptyPage = """{"items":[],"total":0,"limit":20,"offset":0}"""
  private val userJson  =
    """{"id":"user-id","email":"alice@example.com","displayName":"Alice","role":"student"}"""

  List(2147483648L, Long.MaxValue).foreach { total =>
    test(s"catalog and playback pages decode the full Long total $total") {
      val pageJson = s"""{"items":[],"total":$total,"limit":20,"offset":0}"""

      for
        http      <- recordingClient(pageJson)
        catalog    = CatalogApi(uri"/api/catalog", http.client)
        playback   = PlaybackApi(uri"/api/playback", http.client)
        courses   <- catalog.listCourses(ListCoursesParams())
        paths     <- catalog.listLearningPaths(ListLearningPathsParams())
        progress  <- playback.listProgress("token", ProgressQuery())
        favorites <- playback.listFavorites("token", FavoritesQuery())
      yield
        assertEquals(courses.total, total)
        assertEquals(paths.total, total)
        assertEquals(progress.total, total)
        assertEquals(favorites.total, total)
    }
  }

  test("typed API configuration can be passed directly to every client") {
    val config = ApiConfig(
      identityBaseUrl = uri"/api/identity/",
      catalogBaseUrl  = uri"https://example.test/platform/api/catalog/",
      playbackBaseUrl = uri"/api/playback"
    )

    for
      identityHttp <- recordingClient(userJson)
      catalogHttp  <- recordingClient(emptyPage)
      playbackHttp <- recordingClient(emptyPage)
      _            <- IdentityApi(config.identityBaseUrl, identityHttp.client).currentUser("token")
      _ <- CatalogApi(config.catalogBaseUrl, catalogHttp.client).listCourses(ListCoursesParams())
      _ <- PlaybackApi(config.playbackBaseUrl, playbackHttp.client)
             .listFavorites("token", FavoritesQuery())
      identityRequests <- identityHttp.requests.get
      catalogRequests  <- catalogHttp.requests.get
      playbackRequests <- playbackHttp.requests.get
    yield
      assertEquals(identityRequests.head.request.uri, uri"/api/identity/users/me")
      assertEquals(
        catalogRequests.head.request.uri,
        uri"https://example.test/platform/api/catalog/courses"
      )
      assertEquals(playbackRequests.head.request.uri, uri"/api/playback/favorites")
  }

  test("catalog requests encode every supported course filter") {
    for
      http <- recordingClient(emptyPage)
      _    <- CatalogApi(uri"http://localhost:8082", http.client).listCourses(
             ListCoursesParams(
               query      = Some("effect systems"),
               level      = Some(ApiCourseLevel.Intermediate),
               kind       = Some(ApiCourseKind.Talk),
               topic      = Some("Effects & Concurrency"),
               technology = Some("Cats Effect"),
               limit      = Some(20),
               offset     = Some(40)
             )
           )
      requests <- http.requests.get
    yield assertEquals(
      requests.head.request.uri.renderString,
      "http://localhost:8082/courses?q=effect%20systems&level=intermediate&kind=talk&topic=Effects%20%26%20Concurrency&technology=Cats%20Effect&limit=20&offset=40"
    )
  }

  test("catalog supports same-origin API paths and learning path filters") {
    for
      http <- recordingClient(emptyPage)
      _    <- CatalogApi(uri"/api/catalog", http.client).listLearningPaths(
             ListLearningPathsParams(
               query  = Some("typelevel"),
               level  = Some(ApiCourseLevel.Beginner),
               tone   = Some(ApiLearningPathTone.Yellow),
               limit  = Some(10),
               offset = Some(20)
             )
           )
      requests <- http.requests.get
    yield assertEquals(
      requests.head.request.uri.renderString,
      "/api/catalog/learning-paths?q=typelevel&level=beginner&tone=yellow&limit=10&offset=20"
    )
  }

  test("catalog query values round-trip special characters under an existing base path") {
    val query = "Cats + FS2 & café / 100% #?"

    for
      http <- recordingClient(emptyPage)
      _    <- CatalogApi(uri"https://example.test/platform%20demo/api/catalog/", http.client)
             .listCourses(ListCoursesParams(query = Some(query), technology = Some("C++ / JVM")))
      requests <- http.requests.get
    yield
      val uri = Uri.unsafeFromString(requests.head.request.uri.renderString)
      assertEquals(uri.path.renderString, "/platform%20demo/api/catalog/courses")
      assertEquals(uri.query.params, Map("q" -> query, "technology" -> "C++ / JVM"))
      assert(uri.query.renderString.contains("%2B"))
      assert(uri.query.renderString.contains("100%25"))
      assert(uri.fragment.isEmpty)
  }

  test("catalog omits absent and empty filters without dropping zero pagination values") {
    for
      http <- recordingClient(emptyPage)
      api   = CatalogApi(uri"/api/catalog/", http.client)
      _    <- api.listCourses(
             ListCoursesParams(
               query      = Some(""),
               topic      = Some(""),
               technology = Some(""),
               limit      = Some(20),
               offset     = Some(0)
             )
           )
      _        <- api.listLearningPaths(ListLearningPathsParams(query = Some("")))
      requests <- http.requests.get
    yield
      assertEquals(requests.head.request.uri.renderString, "/api/catalog/courses?limit=20&offset=0")
      assertEquals(requests.head.request.uri.query.params, Map("limit" -> "20", "offset" -> "0"))
      assertEquals(requests(1).request.uri.renderString, "/api/catalog/learning-paths")
  }

  test("identity requests use JSON and bearer authentication") {
    for
      registerHttp <- recordingClient(userJson, Status.Created)
      loginHttp    <- recordingClient(
                     """{"accessToken":"token","tokenType":"Bearer","expiresIn":1800}"""
                   )
      currentHttp <- recordingClient(userJson)
      _           <- IdentityApi(uri"http://localhost:8081", registerHttp.client).register(
             RegisterRequest("alice@example.com", "workshop-secret-123", "Alice")
           )
      login <- IdentityApi(uri"http://localhost:8081", loginHttp.client).login(
                 LoginRequest("alice@example.com", "workshop-secret-123")
               )
      _          <- IdentityApi(uri"http://localhost:8081", currentHttp.client).currentUser("token")
      registered <- registerHttp.requests.get
      authenticated <- loginHttp.requests.get
      current       <- currentHttp.requests.get
    yield
      val register = registered.head
      assertEquals(register.request.method, Method.POST)
      assertEquals(header(register.request, "Content-Type"), Some("application/json"))
      assertEquals(
        parse(register.body).toOption.flatMap(_.hcursor.get[String]("displayName").toOption),
        Some("Alice")
      )
      assertEquals(authenticated.head.request.uri.path.renderString, "/auth/login")
      assertEquals(authenticated.head.request.method, Method.POST)
      assertEquals(login.tokenType, TokenType.Bearer)
      assertEquals(header(current.head.request, "Authorization"), Some("Bearer token"))
  }

  test("identity preserves configured absolute base paths with a trailing slash") {
    for
      http <- recordingClient(userJson)
      _    <-
        IdentityApi(uri"https://example.test/platform/identity/", http.client).currentUser("token")
      requests <- http.requests.get
    yield assertEquals(
      requests.head.request.uri.renderString,
      "https://example.test/platform/identity/users/me"
    )
  }

  test("playback requests encode IDs, preserve signed URLs, and do not parse a 204 body") {
    val base = uri"http://localhost:8083"

    for
      playbackHttp <-
        recordingClient(
          """{"url":"http://localhost:9000/video.mp4?signature=a%2Bb","expiresIn":900}"""
        )
      progressHttp <-
        recordingClient(
          """{"courseId":"course/id","lessonId":"lesson/id","positionSeconds":120,"completed":false,"updatedAt":"2026-09-08T00:00:00Z"}"""
        )
      deleteHttp <- recordingClient("", Status.NoContent)
      playback   <-
        PlaybackApi(base, playbackHttp.client).playbackUrl("token", "course/id", "lesson/id")
      _ <- PlaybackApi(base, progressHttp.client)
             .putProgress("token", "course/id", "lesson/id", 120, keepalive = true)
      _                <- PlaybackApi(base, deleteHttp.client).deleteFavorite("token", "course/id")
      playbackRequests <- playbackHttp.requests.get
      progressRequests <- progressHttp.requests.get
      deleteRequests   <- deleteHttp.requests.get
    yield
      assertEquals(playback.url, "http://localhost:9000/video.mp4?signature=a%2Bb")
      assertEquals(
        playbackRequests.head.request.uri.renderString,
        s"$base/courses/course%2Fid/lessons/lesson%2Fid/playback"
      )
      val progress = progressRequests.head
      assertEquals(progress.request.method, Method.PUT)
      assertEquals(
        progress.request.attributes.lookup(FetchOptions.Key).flatMap(_.keepAlive),
        Some(true)
      )
      assertEquals(header(progress.request, "Authorization"), Some("Bearer token"))
      assertEquals(
        parse(progress.body).toOption.flatMap(_.hcursor.get[Int]("positionSeconds").toOption),
        Some(120)
      )
      assertEquals(deleteRequests.size, 1)
      assertEquals(deleteRequests.head.request.method, Method.DELETE)
      assertEquals(deleteRequests.head.request.uri.renderString, s"$base/favorites/course%2Fid")
  }

  test("playback IDs remain individual path segments and are encoded exactly once") {
    val courseId = "course/with space+%2F?#é"
    val lessonId = "lesson/# +%25?"

    for
      http <-
        recordingClient("""{"url":"https://storage.test/video.mp4?sig=a%2Bb","expiresIn":900}""")
      _ <- PlaybackApi(uri"https://example.test/platform%20demo/api/playback/", http.client)
             .playbackUrl("token", courseId, lessonId)
      requests <- http.requests.get
    yield
      val uri = Uri.unsafeFromString(requests.head.request.uri.renderString)
      assertEquals(
        uri.path.segments.map(_.decoded()),
        Vector(
          "platform demo",
          "api",
          "playback",
          "courses",
          courseId,
          "lessons",
          lessonId,
          "playback"
        )
      )
      assert(uri.path.renderString.startsWith("/platform%20demo/api/playback/courses/"))
      assert(uri.path.renderString.contains("%252F"))
      assert(uri.query.isEmpty)
      assert(uri.fragment.isEmpty)
  }

  test("playback filters preserve special characters and false while omitting empty values") {
    val courseId = "course/+%2F & café?"

    for
      http <- recordingClient(emptyPage)
      api   = PlaybackApi(uri"/api/playback/", http.client)
      _    <- api.listProgress(
             "token",
             ProgressQuery(courseId = Some(courseId), completed = Some(false), offset = Some(0))
           )
      _        <- api.listProgress("token", ProgressQuery(courseId = Some("")))
      _        <- api.listFavorites("token", FavoritesQuery(limit = Some(10), offset = Some(0)))
      requests <- http.requests.get
    yield
      assertEquals(requests.head.request.uri.path.renderString, "/api/playback/progress")
      assertEquals(
        Uri.unsafeFromString(requests.head.request.uri.renderString).query.params,
        Map("courseId" -> courseId, "completed" -> "false", "offset" -> "0")
      )
      assertEquals(requests(1).request.uri.renderString, "/api/playback/progress")
      assertEquals(
        requests(2).request.uri.renderString,
        "/api/playback/favorites?limit=10&offset=0"
      )
  }

  List(
    (Status.Unauthorized, "INVALID_CREDENTIALS"),
    (Status.Conflict, "EMAIL_ALREADY_EXISTS"),
    (Status.ServiceUnavailable, "PLAYBACK_UNAVAILABLE")
  ).foreach { (status, code) =>
    test(s"HTTP ${status.code} preserves the modeled error code before the message") {
      for
        http   <- recordingClient(s"""{"code":"$code","message":"Other message"}""", status)
        result <- HttpClient.json[Json](http.client, Request[IO]()).attempt
      yield assertEquals(result, Left(ApiRequestError(code, status.code, Some(code))))
    }
  }

  test("error responses support a message or a plain-text HTTP fallback") {
    for
      messageHttp <- recordingClient("""{"message":"Video not found"}""", Status.NotFound)
      textHttp    <- recordingClient("Bad gateway", Status.BadGateway)
      message     <- HttpClient.json[Json](messageHttp.client, Request[IO]()).attempt
      fallback    <- HttpClient.empty(textHttp.client, Request[IO]()).attempt
    yield
      assertEquals(message, Left(ApiRequestError("Video not found", 404)))
      assertEquals(fallback, Left(ApiRequestError("The API returned HTTP 502.", 502)))
  }

  List("not JSON", "{}").foreach { body =>
    test(s"successful responses reject malformed or mismatched JSON: $body") {
      for
        http   <- recordingClient(body)
        result <- IdentityApi(uri"/api/identity", http.client).currentUser("token").attempt
      yield assertEquals(
        result,
        Left(ApiRequestError("The API returned an invalid JSON response.", 200))
      )
    }
  }

  test("request failures have a safe message and no HTTP status") {
    val client = Client[IO] { _ =>
      Resource.eval(IO.raiseError[Response[IO]](new RuntimeException("Private transport detail")))
    }

    HttpClient.json[Json](client, Request[IO]()).attempt.map { result =>
      assertEquals(result, Left(ApiRequestError("The API could not be reached.", 0)))
    }
  }

  test("response body failures retain the HTTP status and release the response") {
    for
      released <- Ref.of[IO, Boolean](false)
      response  = jsonResponse("", Status.Ok).withBodyStream(
                   Stream.raiseError[IO](new RuntimeException("Private body detail"))
                 )
      client       = Client[IO](_ => Resource.make(IO.pure(response))(_ => released.set(true)))
      result      <- HttpClient.json[Json](client, Request[IO]()).attempt
      wasReleased <- released.get
    yield
      assertEquals(result, Left(ApiRequestError("The API returned an unreadable response.", 200)))
      assert(wasReleased)
  }

  test("canceling a request while reading its body releases the response") {
    for
      reading  <- Deferred[IO, Unit]
      released <- Deferred[IO, Unit]
      response  = jsonResponse("", Status.Ok).withBodyStream(
                   Stream.eval(reading.complete(())).drain ++ Stream.never[IO]
                 )
      client = Client[IO](_ => Resource.make(IO.pure(response))(_ => released.complete(()).void))
      _     <- Resource.make(HttpClient.json[Json](client, Request[IO]()).start)(_.cancel).use {
             fiber =>
               reading.get.timeout(2.seconds) *> fiber.cancel.timeout(2.seconds) *>
                 released.get.timeout(2.seconds)
           }
    yield ()
  }

  private final case class RecordedRequest(request: Request[IO], body: String)

  private final case class RecordingClient(
      client: Client[IO],
      requests: Ref[IO, Vector[RecordedRequest]]
  )

  private def recordingClient(
      body: String,
      status: Status = Status.Ok
  ): IO[RecordingClient] =
    Ref.of[IO, Vector[RecordedRequest]](Vector.empty).map { requests =>
      val client = Client[IO] { request =>
        Resource.eval(
          request.bodyText.compile.string
            .flatMap { body =>
              requests.update(_ :+ RecordedRequest(request, body))
            }
            .as(jsonResponse(body, status))
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
