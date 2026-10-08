package typelevel.courses.data

import java.util.UUID

import org.typelevel.video.streaming.backend.catalog.domain.*
import typelevel.courses.ui.CatalogPresentation.*
import typelevel.courses.ui.{ArtworkVariant, ContentSource, CourseView, LessonView}

object Catalog:
  final private case class Presentation(
      artwork: ArtworkVariant,
      artLabel: String,
      thumbnail: String,
      source: ContentSource,
      outcomes: Vector[String],
      prerequisites: Vector[String],
      featured: Boolean = false,
      isNew: Boolean    = false,
      lessonId: String  = "lesson-1",
      preview: Boolean  = true,
  )

  // The API owns the catalog; these assets and known media IDs only enrich returned courses.
  private val presentationById: Map[String, Presentation] = Map(
    "00000000-0000-0000-0000-000000000104" -> Presentation(
      artwork   = ArtworkVariant.Orbit,
      artLabel  = "JVM",
      thumbnail = "/threads-at-scale-thumbnail.jpg",
      source    = ContentSource(
        "Sphere.it by VirtusLab on YouTube",
        "https://www.youtube.com/watch?v=PLApcas04V0",
      ),
      outcomes = Vector(
        "Connect hardware execution constraints to the JVM thread model",
        "Explain where asynchronous I/O helps and where it does not",
        "Reason about tail latency in high-scale, I/O-bound services",
      ),
      prerequisites = Vector(
        "Basic familiarity with the JVM",
        "An interest in concurrency and service performance",
      ),
      featured = true,
    ),
    "00000000-0000-0000-0000-000000000101" -> Presentation(
      artwork   = ArtworkVariant.Blocks,
      artLabel  = "TL",
      thumbnail = "/typelevel-retrospective-thumbnail.jpg",
      source    = ContentSource(
        "Scala Days Conferences on YouTube",
        "https://www.youtube.com/watch?v=51kW8zK7YhQ",
      ),
      outcomes = Vector(
        "Understand how the Typelevel ecosystem and community evolved",
        "See how cross-platform support influenced the Cats Effect runtime",
        "Learn how newer tools are lowering the barrier to functional Scala",
      ),
      prerequisites = Vector("An interest in Scala and open-source communities"),
    ),
    "00000000-0000-0000-0000-000000000105" -> Presentation(
      artwork   = ArtworkVariant.Stream,
      artLabel  = "CHUNK",
      thumbnail = "/fs2-chunk-thumbnail.jpg",
      source    = ContentSource("Konfy on YouTube", "https://www.youtube.com/watch?v=wOybldcyMLs"),
      outcomes  = Vector(
        "Understand the role Chunk plays inside FS2",
        "See how performance constraints shape data-structure design",
        "Recognize the trade-offs behind the evolution of the Chunk API",
      ),
      prerequisites = Vector("Scala fundamentals", "Some familiarity with FS2 streams"),
    ),
    "00000000-0000-0000-0000-000000000102" -> Presentation(
      artwork   = ArtworkVariant.Grid,
      artLabel  = "CE3",
      thumbnail = "/cats-effect-3-thumbnail.jpg",
      source    = ContentSource("Konfy on YouTube", "https://www.youtube.com/watch?v=JrpFFRdf7Q8"),
      outcomes  = Vector(
        "Understand the core ideas behind Cats Effect 3",
        "Build a mental model for asynchronous and concurrent effects",
        "See how the runtime supports purely functional Scala programs",
      ),
      prerequisites = Vector("Scala fundamentals", "An introduction to functional effects"),
    ),
    "00000000-0000-0000-0000-000000000106" -> Presentation(
      artwork   = ArtworkVariant.Fold,
      artLabel  = "E | A",
      thumbnail = "/rethinking-monad-transformers-thumbnail.jpg",
      source    = ContentSource(
        "Scala Days Conferences on YouTube",
        "https://www.youtube.com/watch?v=nNqx2HiL7cc",
      ),
      outcomes = Vector(
        "Evaluate the trade-offs of conventional monad transformers",
        "Understand how the submarine technique transports typed errors",
        "See how Scala 3 language features support implicit error capabilities",
      ),
      prerequisites = Vector(
        "Scala 3 fundamentals",
        "Familiarity with monadic code and typed errors",
      ),
      isNew = true,
    ),
  )

  private val artworkFallbacks = ArtworkVariant.values.toVector

  // One local editorial recommendation keeps the landing hero available during catalog outages.
  val featured: CourseView = course(
    Course(
      id          = CourseId(UUID.fromString("00000000-0000-0000-0000-000000000104")),
      slug        = CourseSlug.unsafeApply("threads-at-scale"),
      title       = CourseTitle.unsafeApply("Threads at Scale"),
      description = CourseDescription.unsafeApply(
        "Daniel Spiewak connects hardware, JVM threads, asynchronous I/O, and effect systems to practical service performance.",
      ),
      level        = CourseLevel.INTERMEDIATE,
      kind         = CourseKind.TALK,
      topic        = Topic.unsafeApply("Effects & Concurrency"),
      technologies = List("JVM", "Concurrency", "Cats Effect").map(Technology.unsafeApply),
      instructor   = Instructor(
        InstructorName.unsafeApply("Daniel Spiewak"),
        Some(InstructorRole.unsafeApply("Speaker · Sphere.it Conf 2022")),
      ),
      durationSeconds = Some(DurationSeconds.unsafeApply(1849)),
      lessonCount     = Some(LessonCount.unsafeApply(1)),
    ),
    index = 0,
  )

  def course(course: Course, index: Int): CourseView =
    presentationById.get(course.id.value.toString) match
      case Some(presentation) =>
        CourseView(
          course           = course,
          eyebrow          = "Community video",
          shortDescription = course.description.value,
          isVideo          = true,
          featured         = presentation.featured,
          isNew            = presentation.isNew,
          artwork          = presentation.artwork,
          artLabel         = presentation.artLabel,
          thumbnail        = Some(presentation.thumbnail),
          source           = Some(presentation.source),
          outcomes         = presentation.outcomes,
          prerequisites    = presentation.prerequisites,
          lessons          = Vector(
            LessonView(
              id              = presentation.lessonId,
              title           = course.title.value,
              durationSeconds = course.durationSeconds.fold(0)(_.value),
              description     = course.description.value,
              preview         = presentation.preview,
            ),
          ),
        )
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
