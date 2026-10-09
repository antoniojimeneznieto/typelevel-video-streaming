package typelevel.courses.state

import cats.effect.{IO, Resource}
import cats.kernel.Eq
import cats.syntax.all.*
import fs2.concurrent.{Signal, SignallingRef}
import org.typelevel.video.streaming.backend.catalog.api.ListCoursesInput
import org.typelevel.video.streaming.backend.catalog.domain.{LearningPath, PageLimit}
import typelevel.courses.api.CatalogApi
import typelevel.courses.data.Catalog
import typelevel.courses.ui.CourseView

final case class CatalogState(
    courses: Vector[CourseView],
    learningPaths: Vector[LearningPath],
    status: RemoteStateStatus,
):
  def topics: Vector[String] = "All topics" +: courses.map(_.course.topic.value).distinct

object CatalogState:
  private[state] def empty: CatalogState = CatalogState(
    courses       = Vector.empty,
    learningPaths = Vector.empty,
    status        = RemoteStateStatus.Loading,
  )

final class CatalogStore private (
    private val ref: SignallingRef[IO, (Long, CatalogState)],
    private val api: CatalogApi,
):
  val signal: Signal[IO, CatalogState]        = ref.map(_._2).changes(using Eq.fromUniversalEquals)
  val courses: Signal[IO, Vector[CourseView]] =
    signal.map(_.courses).changes(using Eq.fromUniversalEquals)
  val learningPaths: Signal[IO, Vector[LearningPath]] =
    signal.map(_.learningPaths).changes(using Eq.fromUniversalEquals)
  val topics: Signal[IO, Vector[String]] =
    signal.map(_.topics).changes(using Eq.fromUniversalEquals)

  def course(slug: String): Signal[IO, Option[CourseView]] =
    courses.map(_.find(_.course.slug.value == slug)).changes(using Eq.fromUniversalEquals)

  def queryCourses(filters: ListCoursesInput): IO[Vector[CourseView]] =
    api.listCourses(filters).map { page =>
      page.items.toVector.zipWithIndex.map((course, index) => Catalog.course(course, index))
    }

  // A page owns its request: leaving it cancels the fetch, and entering it fetches again.
  def load: Resource[IO, Unit] =
    Resource.eval(markLoading).flatMap(version => fetch(version).background.void)

  def refresh: IO[Unit] = markLoading.flatMap(fetch)

  private def markLoading: IO[Long] =
    ref.modify { (version, state) =>
      val next = version + 1
      (next -> state.copy(status = RemoteStateStatus.Loading)) -> next
    }

  private def fetch(version: Long): IO[Unit] =
    (
      queryCourses(ListCoursesInput(limit = PageLimit.unsafeApply(100))),
      api.listLearningPaths().map(_.items.toVector),
    ).parTupled.attempt.flatMap { result =>
      // The router can mount the next page before releasing the previous page's request.
      ref.update { (current, state) =>
        current -> (if current != version then state
                    else
                      result match
                        case Right((courses, paths)) =>
                          CatalogState(courses, paths, RemoteStateStatus.Ready)
                        case Left(_) => state.copy(status = RemoteStateStatus.Error))
      }
    }

object CatalogStore:
  def resource(api: CatalogApi): Resource[IO, CatalogStore] =
    SignallingRef[IO].of(0L -> CatalogState.empty).toResource.map(ref => CatalogStore(ref, api))
