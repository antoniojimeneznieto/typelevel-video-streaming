package typelevel.courses.state

import java.util.UUID

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.implicits.*
import org.http4s.{Header, Response, Status}
import org.typelevel.ci.CIString
import org.typelevel.video.streaming.backend.catalog.api.ListCoursesInput
import org.typelevel.video.streaming.backend.catalog.domain.*
import smithy4s.json.Json
import typelevel.courses.api.CatalogApi

final class CatalogStoreSuite extends CatsEffectSuite:
  private val backendCourse = Course(
    id              = CourseId(UUID.fromString("00000000-0000-0000-0000-000000000104")),
    slug            = CourseSlug.unsafeApply("threads-at-scale"),
    title           = CourseTitle.unsafeApply("Updated backend title"),
    description     = CourseDescription.unsafeApply("Updated backend description"),
    level           = CourseLevel.BEGINNER,
    kind            = CourseKind.TALK,
    topic           = Topic.unsafeApply("Backend topic"),
    technologies    = List(Technology.unsafeApply("Scala")),
    instructor      = Instructor(InstructorName.unsafeApply("Backend speaker"), None),
    durationSeconds = Some(DurationSeconds.unsafeApply(120)),
    lessonCount     = Some(LessonCount.unsafeApply(2)),
  )
  private val otherCourse = backendCourse.copy(
    id    = CourseId(UUID.fromString("99999999-9999-9999-9999-999999999999")),
    slug  = CourseSlug.unsafeApply("new-backend-course"),
    title = CourseTitle.unsafeApply("New backend course"),
  )
  private val backendPath = LearningPath(
    id          = LearningPathId.unsafeApply("discover-typelevel"),
    title       = LearningPathTitle.unsafeApply("Updated backend path"),
    description = LearningPathDescription.unsafeApply("A path from the backend"),
    timeLabel   = TimeLabel.unsafeApply("2 minutes"),
    level       = CourseLevel.ADVANCED,
    tone        = LearningPathTone.CORAL,
    courseIds   = List(backendCourse.id),
  )

  test("creating the store neither fetches nor invents catalog data") {
    for
      calls <- Ref.of[IO, Int](0)
      _     <- storeResource(
             calls.update(_ + 1).as(coursesResponse(backendCourse)),
             calls.update(_ + 1).as(pathsResponse(backendPath)),
           ).use { store =>
             for
               state <- store.signal.get
               count <- calls.get
             yield
               assertEquals(state.status, RemoteStateStatus.Loading)
               assertEquals(state.courses, Vector.empty)
               assertEquals(state.learningPaths, Vector.empty)
               assertEquals(state.topics, Vector("All topics"))
               assertEquals(count, 0)
           }
    yield ()
  }

  test("load requests both endpoints in parallel and stays Loading until both complete") {
    for
      coursesStarted <- Deferred[IO, Unit]
      pathsStarted   <- Deferred[IO, Unit]
      coursesRead    <- Deferred[IO, Unit]
      coursesReply   <- Deferred[IO, Response[IO]]
      pathsReply     <- Deferred[IO, Response[IO]]
      _              <- storeResource(
             coursesStarted.complete(()).void *> coursesReply.get,
             pathsStarted.complete(()).void *> pathsReply.get,
           ).use { store =>
             store.load.use { _ =>
               for
                 _       <- (coursesStarted.get, pathsStarted.get).parTupled
                 initial <- store.signal.get
                 response = coursesResponse(backendCourse)
                 _       <- coursesReply.complete(
                        response.withBodyStream(
                          response.body.onFinalize(coursesRead.complete(()).void),
                        ),
                      )
                 _       <- coursesRead.get
                 partial <- store.signal.get
                 _       <- pathsReply.complete(pathsResponse(backendPath))
                 ready   <- awaitStatus(store, RemoteStateStatus.Ready)
               yield
                 assertEquals(initial.status, RemoteStateStatus.Loading)
                 assertEquals(initial.courses, Vector.empty)
                 assertEquals(partial.status, RemoteStateStatus.Loading)
                 assertEquals(partial.courses, Vector.empty)
                 assertEquals(partial.learningPaths, Vector.empty)
                 assertEquals(ready.courses.map(_.course), Vector(backendCourse))
                 assertEquals(ready.learningPaths, Vector(backendPath))
             }
           }
    yield ()
  }

  test("queryCourses preserves backend metadata and order without adding local courses") {
    storeResource(
      IO.pure(coursesResponse(otherCourse, backendCourse)),
      IO.pure(pathsResponse()),
    ).use { store =>
      store.queryCourses(ListCoursesInput()).map { courses =>
        assertEquals(courses.map(_.course), Vector(otherCourse, backendCourse))
      }
    }
  }

  test("an empty successful catalog is Ready") {
    storeResource(IO.pure(coursesResponse()), IO.pure(pathsResponse())).use { store =>
      for
        _     <- store.refresh
        state <- store.signal.get
      yield
        assertEquals(state.status, RemoteStateStatus.Ready)
        assertEquals(state.courses, Vector.empty)
        assertEquals(state.learningPaths, Vector.empty)
    }
  }

  List("courses", "learning paths").foreach { failingEndpoint =>
    test(s"an initial $failingEndpoint failure leaves an empty Error state") {
      val failure = Response[IO](Status.ServiceUnavailable)
      storeResource(
        IO.pure(if failingEndpoint == "courses" then failure else coursesResponse(backendCourse)),
        IO.pure(if failingEndpoint == "learning paths" then failure else pathsResponse(backendPath)),
      ).use { store =>
        for
          _     <- store.refresh
          state <- store.signal.get
        yield
          assertEquals(state.status, RemoteStateStatus.Error)
          assertEquals(state.courses, Vector.empty)
          assertEquals(state.learningPaths, Vector.empty)
      }
    }
  }

  test("refresh failure retains fetched data as Error and a retry replaces it") {
    for
      reply <- Ref.of[IO, Response[IO]](coursesResponse(backendCourse))
      _     <- storeResource(reply.get, IO.pure(pathsResponse(backendPath))).use { store =>
             for
               _       <- store.refresh
               initial <- store.signal.get
               _       <- reply.set(Response[IO](Status.ServiceUnavailable))
               _       <- store.refresh
               failed  <- store.signal.get
               _       <- reply.set(coursesResponse(otherCourse))
               _       <- store.refresh
               retried <- store.signal.get
             yield
               assertEquals(initial.status, RemoteStateStatus.Ready)
               assertEquals(initial.courses.map(_.course), Vector(backendCourse))
               assertEquals(failed, initial.copy(status = RemoteStateStatus.Error))
               assertEquals(retried.status, RemoteStateStatus.Ready)
               assertEquals(retried.courses.map(_.course), Vector(otherCourse))
               assertEquals(retried.learningPaths, Vector(backendPath))
           }
    yield ()
  }

  test("leaving a page cancels both requests and cannot overwrite the next page's result") {
    for
      coursesStarted   <- Deferred[IO, Unit]
      pathsStarted     <- Deferred[IO, Unit]
      coursesCancelled <- Deferred[IO, Unit]
      pathsCancelled   <- Deferred[IO, Unit]
      oldCourses       <- Deferred[IO, Response[IO]]
      oldPaths         <- Deferred[IO, Response[IO]]
      courseCalls      <- Ref.of[IO, Int](0)
      pathCalls        <- Ref.of[IO, Int](0)
      _                <- storeResource(
             courseCalls.getAndUpdate(_ + 1).flatMap {
               case 0 =>
                 (coursesStarted.complete(()).void *> oldCourses.get)
                   .onCancel(coursesCancelled.complete(()).void)
               case _ => IO.pure(coursesResponse(otherCourse))
             },
             pathCalls.getAndUpdate(_ + 1).flatMap {
               case 0 =>
                 (pathsStarted.complete(()).void *> oldPaths.get)
                   .onCancel(pathsCancelled.complete(()).void)
               case _ => IO.pure(pathsResponse())
             },
           ).use { store =>
             for
               _ <- store.load.use(_ => (coursesStarted.get, pathsStarted.get).parTupled.void)
               cancelledCourses <- coursesCancelled.tryGet
               cancelledPaths   <- pathsCancelled.tryGet
               _                <- IO {
                      assertEquals(cancelledCourses, Some(()))
                      assertEquals(cancelledPaths, Some(()))
                    }
               _ <- store.load.use { _ =>
                      for
                        ready <- awaitStatus(store, RemoteStateStatus.Ready)
                        _     <- oldCourses.complete(coursesResponse(backendCourse))
                        _     <- oldPaths.complete(pathsResponse(backendPath))
                        state <- store.signal.get
                      yield
                        assertEquals(ready.courses.map(_.course), Vector(otherCourse))
                        assertEquals(ready.learningPaths, Vector.empty)
                        assertEquals(state, ready)
                    }
             yield ()
           }
    yield ()
  }

  test("an older refresh completing late cannot overwrite a newer refresh") {
    for
      firstStarted  <- Deferred[IO, Unit]
      firstFinished <- Deferred[IO, Unit]
      firstReply    <- Deferred[IO, Response[IO]]
      courseCalls   <- Ref.of[IO, Int](0)
      _             <- storeResource(
             courseCalls.getAndUpdate(_ + 1).flatMap {
               case 0 => firstStarted.complete(()).void *> firstReply.get
               case _ => IO.pure(coursesResponse(otherCourse))
             },
             IO.pure(pathsResponse()),
           ).use { store =>
             (store.refresh *> firstFinished.complete(()).void).background.use { _ =>
               for
                 _      <- firstStarted.get
                 _      <- store.refresh
                 newest <- store.signal.get
                 _      <- firstReply.complete(coursesResponse(backendCourse))
                 _      <- firstFinished.get
                 state  <- store.signal.get
               yield
                 assertEquals(newest.status, RemoteStateStatus.Ready)
                 assertEquals(newest.courses.map(_.course), Vector(otherCourse))
                 assertEquals(state, newest)
             }
           }
    yield ()
  }

  private def storeResource(
      courses: IO[Response[IO]],
      paths: IO[Response[IO]],
  ): Resource[IO, CatalogStore] =
    val client = Client[IO] { request =>
      Resource.eval {
        request.uri.path.renderString match
          case "/api/catalog/courses" => courses
          case "/api/catalog/learning-paths" => paths
          case path => IO.raiseError(new IllegalArgumentException(s"Unexpected request: $path"))
      }
    }
    CatalogStore.resource(CatalogApi(uri"/api/catalog", client))

  private def awaitStatus(store: CatalogStore, status: RemoteStateStatus): IO[CatalogState] =
    store.signal.discrete.find(_.status == status).compile.lastOrError

  private def coursesResponse(courses: Course*): Response[IO] =
    jsonResponse(
      Json
        .writeBlob(
          CoursePage(
            courses.toList,
            TotalCount.unsafeApply(courses.size.toLong),
            PageLimit.unsafeApply(100),
            PageOffset.unsafeApply(0),
          ),
        )
        .toUTF8String,
    )

  private def pathsResponse(paths: LearningPath*): Response[IO] =
    jsonResponse(
      Json
        .writeBlob(
          LearningPathPage(
            paths.toList,
            TotalCount.unsafeApply(paths.size.toLong),
            PageLimit.unsafeApply(20),
            PageOffset.unsafeApply(0),
          ),
        )
        .toUTF8String,
    )

  private def jsonResponse(body: String): Response[IO] =
    Response[IO](Status.Ok)
      .withEntity(body)
      .putHeaders(Header.Raw(CIString("Content-Type"), "application/json"))
