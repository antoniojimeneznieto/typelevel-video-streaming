package typelevel.courses.state

import cats.effect.{IO, Resource}
import cats.kernel.Eq
import cats.syntax.all.*
import fs2.concurrent.{Signal, SignallingRef}
import org.typelevel.video.streaming.backend.catalog.api.ListCoursesInput
import org.typelevel.video.streaming.backend.catalog.domain.{Course, LearningPath, PageLimit}
import typelevel.courses.api.CatalogApi
import typelevel.courses.data.Catalog
import typelevel.courses.ui.CatalogPresentation.*
import typelevel.courses.ui.{ArtworkVariant, CourseView}

final case class CatalogState(
    courses: Vector[CourseView],
    learningPaths: Vector[LearningPath],
):
  def topics: Vector[String] = "All topics" +: courses.map(_.course.topic.value).distinct

object CatalogState:
  private[state] def seeded: CatalogState = CatalogState(
    courses       = Catalog.courses,
    learningPaths = Catalog.learningPaths,
  )

final class CatalogStore private (
    private val ref: SignallingRef[IO, CatalogState],
    private val api: CatalogApi,
):
  val signal: Signal[IO, CatalogState]        = ref.changes(using Eq.fromUniversalEquals)
  val courses: Signal[IO, Vector[CourseView]] =
    signal.map(_.courses).changes(using Eq.fromUniversalEquals)
  val learningPaths: Signal[IO, Vector[LearningPath]] =
    signal.map(_.learningPaths).changes(using Eq.fromUniversalEquals)
  val topics: Signal[IO, Vector[String]] =
    signal.map(_.topics).changes(using Eq.fromUniversalEquals)

  def course(slug: String): Signal[IO, Option[CourseView]] =
    courses.map(_.find(_.course.slug.value == slug)).changes(using Eq.fromUniversalEquals)

  def queryCourses(filters: ListCoursesInput): IO[Vector[CourseView]] =
    api.listCourses(filters).map(page => CatalogHydration.courses(page.items.toVector))

  private def loadInitial: IO[Unit] =
    (
      queryCourses(ListCoursesInput(limit = PageLimit.unsafeApply(100))).attempt,
      api
        .listLearningPaths()
        .map(page => CatalogHydration.learningPaths(page.items.toVector))
        .attempt,
    ).parTupled.flatMap { (coursesResult, pathsResult) =>
      ref.update { current =>
        current.copy(
          courses       = coursesResult.getOrElse(current.courses),
          learningPaths = pathsResult.getOrElse(current.learningPaths),
        )
      }
    }

object CatalogStore:
  def resource(api: CatalogApi): Resource[IO, CatalogStore] = for
    ref  <- SignallingRef[IO].of(CatalogState.seeded).toResource
    store = CatalogStore(ref, api)
    _    <- store.loadInitial.background
  yield store

private[state] object CatalogHydration:
  private val artworkFallbacks = ArtworkVariant.values.toVector
  private val courseOrder      =
    Catalog.courses.zipWithIndex.map((view, index) => view.course.id -> index).toMap
  private val pathOrder =
    Catalog.learningPaths.zipWithIndex.map((path, index) => path.id -> index).toMap

  def courses(items: Vector[Course]): Vector[CourseView] =
    items.zipWithIndex
      .map((course, index) => courseFromApi(course, index))
      .sortBy(view => courseOrder.getOrElse(view.course.id, Int.MaxValue))

  def learningPaths(items: Vector[LearningPath]): Vector[LearningPath] =
    items.sortBy(path => pathOrder.getOrElse(path.id, Int.MaxValue))

  private def courseFromApi(course: Course, index: Int): CourseView =
    Catalog.courses.find(view =>
      view.course.id == course.id || view.course.slug == course.slug,
    ) match
      case Some(video) if video.isVideo =>
        video.copy(course =
          video.course.copy(
            id              = course.id,
            durationSeconds = video.course.durationSeconds.orElse(course.durationSeconds),
          ),
        )
      case Some(seed) => seed.copy(course = course, shortDescription = course.description.value)
      case None =>
        CourseView(
          course           = course,
          eyebrow          = course.kind.label,
          shortDescription = course.description.value,
          artwork          = artworkFallbacks(index % artworkFallbacks.size),
          artLabel         = artLabel(course.title.value),
        )

  private def artLabel(title: String): String =
    val result = title
      .split("\\s+")
      .filter(_.exists(_.isLetterOrDigit))
      .take(2)
      .flatMap(_.find(_.isLetterOrDigit))
      .mkString
      .toUpperCase
    if result.isEmpty then "TL" else result
