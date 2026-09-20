package typelevel.courses.state

import cats.effect.{IO, Resource}
import cats.kernel.Eq
import cats.syntax.all.*
import fs2.concurrent.{Signal, SignallingRef}
import typelevel.courses.api.*
import typelevel.courses.data.Catalog
import typelevel.courses.domain.*

enum CatalogStatus:
  case Loading, Ready, Error

final case class CatalogState(
    courses: Vector[Course],
    learningPaths: Vector[LearningPath],
    status: CatalogStatus,
    error: Option[String],
):
  def topics: Vector[String] = "All topics" +: courses.map(_.topic).distinct

object CatalogState:
  private[state] def seeded: CatalogState = CatalogState(
    courses       = Catalog.courses,
    learningPaths = Catalog.learningPaths,
    status        = CatalogStatus.Loading,
    error         = None,
  )

final class CatalogStore private (
    private val ref: SignallingRef[IO, CatalogState],
    private val api: CatalogApi,
):
  val signal: Signal[IO, CatalogState]    = ref.changes(using Eq.fromUniversalEquals)
  val courses: Signal[IO, Vector[Course]] =
    signal.map(_.courses).changes(using Eq.fromUniversalEquals)
  val learningPaths: Signal[IO, Vector[LearningPath]] =
    signal.map(_.learningPaths).changes(using Eq.fromUniversalEquals)
  val topics: Signal[IO, Vector[String]] =
    signal.map(_.topics).changes(using Eq.fromUniversalEquals)
  val status: Signal[IO, CatalogStatus] =
    signal.map(_.status).changes(using Eq.fromUniversalEquals)
  val error: Signal[IO, Option[String]] =
    signal.map(_.error).changes(using Eq.fromUniversalEquals)

  def snapshot: IO[CatalogState] = ref.get

  def getCourse(slug: String): IO[Option[Course]] =
    ref.get.map(_.courses.find(_.slug == slug))

  def course(slug: String): Signal[IO, Option[Course]] =
    courses.map(_.find(_.slug == slug)).changes(using Eq.fromUniversalEquals)

  def queryCourses(filters: ListCoursesParams): IO[Vector[Course]] =
    api.listCourses(filters).map(page => CatalogHydration.courses(page.items))

  private[state] def loadInitial: IO[Unit] =
    (
      api.listCourses(ListCoursesParams(limit = Some(100), offset = Some(0))).attempt,
      api.listLearningPaths().attempt,
    ).parTupled.flatMap { (coursesResult, pathsResult) =>
      ref.update { current =>
        val nextCourses =
          coursesResult.fold(_ => current.courses, page => CatalogHydration.courses(page.items))
        val nextPaths = pathsResult.fold(
          _ => current.learningPaths,
          page => CatalogHydration.learningPaths(page.items),
        )
        val failures = Vector(
          Option.when(coursesResult.isLeft)("courses"),
          Option.when(pathsResult.isLeft)("learning paths"),
        ).flatten

        current.copy(
          courses       = nextCourses,
          learningPaths = nextPaths,
          status        = if failures.isEmpty then CatalogStatus.Ready else CatalogStatus.Error,
          error         = Option.when(failures.nonEmpty)(
            s"Could not load ${failures.mkString(" or ")} from the catalog API.",
          ),
        )
      }
    }

object CatalogStore:
  def resource(api: CatalogApi): Resource[IO, CatalogStore] = for
    ref  <- SignallingRef[IO].of(CatalogState.seeded).toResource
    store = CatalogStore(ref, api)
    _    <- store.loadInitial.background
  yield store

  private[state] def inMemory(api: CatalogApi): IO[CatalogStore] =
    SignallingRef[IO].of(CatalogState.seeded).map(CatalogStore(_, api))

private[state] object CatalogHydration:
  private val levels = Map(
    ApiCourseLevel.Beginner -> CourseLevel.Beginner,
    ApiCourseLevel.Intermediate -> CourseLevel.Intermediate,
    ApiCourseLevel.Advanced -> CourseLevel.Advanced,
  )
  private val formats = Map(
    ApiCourseKind.Course -> CourseFormat.Course,
    ApiCourseKind.Workshop -> CourseFormat.Workshop,
    ApiCourseKind.Talk -> CourseFormat.Talk,
  )
  private val tones = Map(
    ApiLearningPathTone.Yellow -> PathTone.Yellow,
    ApiLearningPathTone.Purple -> PathTone.Purple,
    ApiLearningPathTone.Coral -> PathTone.Coral,
  )
  private val artworkFallbacks = ArtworkVariant.values.toVector

  def courses(items: Vector[ApiCourse]): Vector[Course] =
    val seedOrder = Catalog.courses.zipWithIndex.map((course, index) => course.id -> index).toMap
    items.zipWithIndex
      .map((course, index) => courseFromApi(course, index))
      .sortBy(course => seedOrder.getOrElse(course.id, Int.MaxValue))

  def learningPaths(items: Vector[ApiLearningPath]): Vector[LearningPath] =
    val seedOrder = Catalog.learningPaths.zipWithIndex.map((path, index) => path.id -> index).toMap
    items
      .map(pathFromApi)
      .sortBy(path => seedOrder.getOrElse(path.id, Int.MaxValue))

  private def courseFromApi(course: ApiCourse, index: Int): Course =
    val seed = Catalog.courses.find(item => item.id == course.id || item.slug == course.slug)
    seed.filter(_.format == CourseFormat.Video) match
      case Some(video) =>
        video.copy(
          id              = course.id,
          durationSeconds = video.durationSeconds.orElse(course.durationSeconds),
        )
      case None => backendCourse(course, index, seed)

  private def backendCourse(course: ApiCourse, index: Int, seed: Option[Course]): Course =
    Course(
      id               = course.id,
      slug             = course.slug,
      title            = course.title,
      eyebrow          = seed.map(_.eyebrow).getOrElse(formats(course.kind).label),
      shortDescription = course.description,
      description      = course.description,
      level            = levels(course.level),
      format           = formats(course.kind),
      topic            = course.topic,
      technologies     = course.technologies,
      duration         = course.durationSeconds
        .map(formatDuration)
        .orElse(seed.map(_.duration))
        .getOrElse("Duration coming soon"),
      durationSeconds = course.durationSeconds.orElse(seed.flatMap(_.durationSeconds)),
      lessonCount     = course.lessonCount.orElse(seed.map(_.lessonCount)).getOrElse(0),
      rating          = seed.flatMap(_.rating),
      students        = seed.flatMap(_.students),
      year            = seed.flatMap(_.year),
      featured        = seed.exists(_.featured),
      isNew           = seed.exists(_.isNew),
      artwork         = seed
        .map(_.artwork)
        .getOrElse(
          artworkFallbacks(index % artworkFallbacks.size),
        ),
      artLabel   = seed.map(_.artLabel).getOrElse(artLabel(course.title)),
      thumbnail  = seed.flatMap(_.thumbnail),
      source     = seed.flatMap(_.source),
      instructor = Instructor(
        name = course.instructor.name,
        role = course.instructor.role
          .orElse(seed.map(_.instructor.role))
          .getOrElse(""),
        initials = initials(course.instructor.name),
      ),
      outcomes      = seed.fold(Vector.empty[String])(_.outcomes),
      prerequisites = seed.fold(Vector.empty[String])(_.prerequisites),
      lessons       = seed.fold(Vector.empty[Lesson])(_.lessons),
    )

  private def pathFromApi(path: ApiLearningPath): LearningPath = LearningPath(
    id          = path.id,
    title       = path.title,
    description = path.description,
    courseIds   = path.courseIds,
    time        = path.timeLabel,
    level       = levels(path.level),
    tone        = tones(path.tone),
  )

  private def formatDuration(seconds: Int): String =
    val totalMinutes = math.max(0, math.round(seconds.toDouble / 60).toInt)
    val hours        = totalMinutes / 60
    val minutes      = totalMinutes % 60
    if hours == 0 then s"${minutes}m"
    else if minutes == 0 then s"${hours}h"
    else s"${hours}h ${minutes}m"

  private def initials(name: String): String =
    val result = name.trim.split("\\s+").take(2).flatMap(_.headOption).mkString.toUpperCase
    if result.isEmpty then "?" else result

  private def artLabel(title: String): String =
    val result = title
      .split("\\s+")
      .filter(_.exists(_.isLetterOrDigit))
      .take(2)
      .flatMap(_.find(_.isLetterOrDigit))
      .mkString
      .toUpperCase
    if result.isEmpty then "TL" else result
