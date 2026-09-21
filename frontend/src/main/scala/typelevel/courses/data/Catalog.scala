package typelevel.courses.data

import java.util.UUID

import org.typelevel.video.streaming.backend.catalog.domain.*
import typelevel.courses.ui.{ArtworkVariant, ContentSource, CourseView, LessonView}

object Catalog:
  private val defaultLessonDescription =
    "Follow along with a focused explanation, practical examples, and a small exercise."

  private def lessons(items: (String, String, Option[String])*): Vector[LessonView] =
    items.zipWithIndex.map { case ((title, duration, description), index) =>
      val durationParts   = duration.split(':').toList.flatMap(_.toIntOption)
      val durationSeconds = durationParts match
        case minutes :: seconds :: Nil => minutes * 60 + seconds
        case _ => 0

      LessonView(
        id              = s"lesson-${index + 1}",
        title           = title,
        durationSeconds = durationSeconds,
        description     = description.getOrElse(defaultLessonDescription),
        preview         = index == 0,
      )
    }.toVector

  private def lesson(
      title: String,
      duration: String,
      description: String,
  ): (String, String, Option[String]) = (title, duration, Some(description))

  private def literal[A](value: Either[String, A]): A =
    value.fold(message => throw new IllegalArgumentException(message), identity)

  val courses: Vector[CourseView] = Vector(
    CourseView(
      course = Course(
        id          = CourseId(UUID.fromString("00000000-0000-0000-0000-000000000104")),
        slug        = literal(CourseSlug("threads-at-scale")),
        title       = literal(CourseTitle("Threads at Scale")),
        description = literal(
          CourseDescription(
            "Daniel Spiewak connects the hardware beneath our programs to JVM threads, asynchronous I/O, and high-level effect systems. The talk develops a practical model for reasoning about performance and improving tail latency in I/O-bound services.",
          ),
        ),
        level        = CourseLevel.INTERMEDIATE,
        kind         = CourseKind.TALK,
        topic        = literal(Topic("Effects & Concurrency")),
        technologies =
          List("JVM", "Concurrency", "Cats Effect").map(value => literal(Technology(value))),
        instructor = Instructor(
          literal(InstructorName("Daniel Spiewak")),
          Some(literal(InstructorRole("Speaker · Sphere.it Conf 2022"))),
        ),
        durationSeconds = Some(literal(DurationSeconds(1849))),
        lessonCount     = Some(literal(LessonCount(1))),
      ),
      eyebrow          = "Community video",
      shortDescription =
        "Follow the path from hardware and JVM threads to asynchronous I/O and effect-system abstractions.",
      isVideo   = true,
      rating    = None,
      students  = None,
      featured  = true,
      isNew     = false,
      artwork   = ArtworkVariant.Orbit,
      artLabel  = "JVM",
      thumbnail = Some("/threads-at-scale-thumbnail.jpg"),
      source    = Some(
        ContentSource(
          "Sphere.it by VirtusLab on YouTube",
          "https://www.youtube.com/watch?v=PLApcas04V0",
        ),
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
      lessons = lessons(
        lesson(
          "Threads at Scale",
          "30:49",
          "From raw hardware and JVM threads to asynchronous I/O, effect systems, and practical latency improvements.",
        ),
      ),
    ),
    CourseView(
      course = Course(
        id          = CourseId(UUID.fromString("00000000-0000-0000-0000-000000000101")),
        slug        = literal(CourseSlug("typelevel-retrospective")),
        title       = literal(CourseTitle("A Typelevel Retrospective")),
        description = literal(
          CourseDescription(
            "Arman Bilge looks back at the people and projects that shaped the modern Typelevel ecosystem. The talk covers cross-platform libraries, the evolution of the Cats Effect runtime, and the tools making functional Scala more approachable.",
          ),
        ),
        level        = CourseLevel.BEGINNER,
        kind         = CourseKind.TALK,
        topic        = literal(Topic("Typelevel Community")),
        technologies =
          List("Typelevel", "Scala", "Cats Effect").map(value => literal(Technology(value))),
        instructor = Instructor(
          literal(InstructorName("Arman Bilge")),
          Some(literal(InstructorRole("Typelevel · Scala Days 2025"))),
        ),
        durationSeconds = Some(literal(DurationSeconds(2142))),
        lessonCount     = Some(literal(LessonCount(1))),
      ),
      eyebrow          = "Community video",
      shortDescription =
        "Trace the growth of Typelevel’s community, libraries, runtimes, and tools with Arman Bilge.",
      isVideo   = true,
      rating    = None,
      students  = None,
      featured  = false,
      isNew     = false,
      artwork   = ArtworkVariant.Blocks,
      artLabel  = "TL",
      thumbnail = Some("/typelevel-retrospective-thumbnail.jpg"),
      source    = Some(
        ContentSource(
          "Scala Days Conferences on YouTube",
          "https://www.youtube.com/watch?v=51kW8zK7YhQ",
        ),
      ),
      outcomes = Vector(
        "Understand how the Typelevel ecosystem and community evolved",
        "See how cross-platform support influenced the Cats Effect runtime",
        "Learn how newer tools are lowering the barrier to functional Scala",
      ),
      prerequisites = Vector("An interest in Scala and open-source communities"),
      lessons       = lessons(
        lesson(
          "A Typelevel Retrospective",
          "35:42",
          "A tour through Typelevel’s community growth, cross-platform ecosystem, runtime work, and efforts to make the stack easier to adopt.",
        ),
      ),
    ),
    CourseView(
      course = Course(
        id          = CourseId(UUID.fromString("00000000-0000-0000-0000-000000000105")),
        slug        = literal(CourseSlug("fs2-chunk")),
        title       = literal(CourseTitle("fs2.Chunk")),
        description = literal(
          CourseDescription(
            "Michael Pilquist explores the design and evolution of fs2.Chunk, the data structure at the heart of FS2. The talk shows how practical performance and API constraints influence a foundational streaming abstraction.",
          ),
        ),
        level        = CourseLevel.ADVANCED,
        kind         = CourseKind.TALK,
        topic        = literal(Topic("Streaming")),
        technologies =
          List("FS2", "Scala", "Functional Streaming").map(value => literal(Technology(value))),
        instructor = Instructor(
          literal(InstructorName("Michael Pilquist")),
          Some(literal(InstructorRole("Speaker · Scala Love 2022"))),
        ),
        durationSeconds = Some(literal(DurationSeconds(2874))),
        lessonCount     = Some(literal(LessonCount(1))),
      ),
      eyebrow          = "Community video",
      shortDescription =
        "Look inside the data structure that powers FS2 and see how real constraints shaped its evolution.",
      isVideo   = true,
      rating    = None,
      students  = None,
      featured  = false,
      isNew     = false,
      artwork   = ArtworkVariant.Stream,
      artLabel  = "CHUNK",
      thumbnail = Some("/fs2-chunk-thumbnail.jpg"),
      source    = Some(
        ContentSource("Konfy on YouTube", "https://www.youtube.com/watch?v=wOybldcyMLs"),
      ),
      outcomes = Vector(
        "Understand the role Chunk plays inside FS2",
        "See how performance constraints shape data-structure design",
        "Recognize the trade-offs behind the evolution of the Chunk API",
      ),
      prerequisites = Vector("Scala fundamentals", "Some familiarity with FS2 streams"),
      lessons       = lessons(
        lesson(
          "fs2.Chunk",
          "47:54",
          "A close look at the structure that powers FS2, its evolution, and the constraints that shaped its design.",
        ),
      ),
    ),
    CourseView(
      course = Course(
        id          = CourseId(UUID.fromString("00000000-0000-0000-0000-000000000102")),
        slug        = literal(CourseSlug("cats-effect-3")),
        title       = literal(CourseTitle("Cats Effect 3")),
        description = literal(
          CourseDescription(
            "Daniel Spiewak presents Cats Effect 3 and develops a practical mental model for functional asynchronous and concurrent programs in Scala.",
          ),
        ),
        level        = CourseLevel.INTERMEDIATE,
        kind         = CourseKind.TALK,
        topic        = literal(Topic("Effects & Concurrency")),
        technologies =
          List("Cats Effect", "Scala", "Concurrency").map(value => literal(Technology(value))),
        instructor = Instructor(
          literal(InstructorName("Daniel Spiewak")),
          Some(literal(InstructorRole("Speaker · Scala Love"))),
        ),
        durationSeconds = Some(literal(DurationSeconds(2370))),
        lessonCount     = Some(literal(LessonCount(1))),
      ),
      eyebrow          = "Community video",
      shortDescription =
        "Explore the ideas and execution model behind the third generation of Cats Effect.",
      isVideo   = true,
      rating    = None,
      students  = None,
      featured  = false,
      isNew     = false,
      artwork   = ArtworkVariant.Grid,
      artLabel  = "CE3",
      thumbnail = Some("/cats-effect-3-thumbnail.jpg"),
      source    = Some(
        ContentSource("Konfy on YouTube", "https://www.youtube.com/watch?v=JrpFFRdf7Q8"),
      ),
      outcomes = Vector(
        "Understand the core ideas behind Cats Effect 3",
        "Build a mental model for asynchronous and concurrent effects",
        "See how the runtime supports purely functional Scala programs",
      ),
      prerequisites = Vector("Scala fundamentals", "An introduction to functional effects"),
      lessons       = lessons(
        lesson(
          "Cats Effect 3",
          "39:30",
          "Daniel Spiewak introduces Cats Effect 3 and its model for functional asynchronous and concurrent programming.",
        ),
      ),
    ),
    CourseView(
      course = Course(
        id          = CourseId(UUID.fromString("00000000-0000-0000-0000-000000000106")),
        slug        = literal(CourseSlug("rethinking-monad-transformers")),
        title       = literal(CourseTitle("Rethinking Monad Transformers")),
        description = literal(
          CourseDescription(
            "Thanh Le presents the “submarine” technique for carrying arbitrary typed errors through an effect’s Throwable channel. The talk uses Scala 3 context functions, inline definitions, and implicit capabilities to rethink familiar monad-transformer trade-offs.",
          ),
        ),
        level        = CourseLevel.ADVANCED,
        kind         = CourseKind.TALK,
        topic        = literal(Topic("Error Handling")),
        technologies = List("Scala 3", "Functional Programming", "Typed Errors").map(value =>
          literal(Technology(value)),
        ),
        instructor = Instructor(
          literal(InstructorName("Thanh Le")),
          Some(literal(InstructorRole("Speaker · Scala Days 2025"))),
        ),
        durationSeconds = Some(literal(DurationSeconds(2007))),
        lessonCount     = Some(literal(LessonCount(1))),
      ),
      eyebrow          = "Community video",
      shortDescription =
        "Explore a different way to carry typed errors through effectful Scala programs.",
      isVideo   = true,
      rating    = None,
      students  = None,
      featured  = false,
      isNew     = true,
      artwork   = ArtworkVariant.Fold,
      artLabel  = "E | A",
      thumbnail = Some("/rethinking-monad-transformers-thumbnail.jpg"),
      source    = Some(
        ContentSource(
          "Scala Days Conferences on YouTube",
          "https://www.youtube.com/watch?v=nNqx2HiL7cc",
        ),
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
      lessons = lessons(
        lesson(
          "Rethinking Monad Transformers",
          "33:27",
          "A Scala 3 approach to carrying typed errors through an effect without a conventional transformer stack.",
        ),
      ),
    ),
  )

  val learningPaths: Vector[LearningPath] = Vector(
    LearningPath(
      id          = literal(LearningPathId("discover-typelevel")),
      title       = literal(LearningPathTitle("Discover Typelevel")),
      description = literal(
        LearningPathDescription("Explore the ecosystem, its community, and its core ideas."),
      ),
      courseIds = List(
        "00000000-0000-0000-0000-000000000101",
        "00000000-0000-0000-0000-000000000102",
      ).map(value => CourseId(UUID.fromString(value))),
      timeLabel = literal(TimeLabel("1 hour 15 minutes")),
      level     = CourseLevel.BEGINNER,
      tone      = LearningPathTone.YELLOW,
    ),
    LearningPath(
      id          = literal(LearningPathId("inside-typelevel")),
      title       = literal(LearningPathTitle("Inside Typelevel")),
      description = literal(
        LearningPathDescription(
          "Explore the design decisions behind effects, concurrency, streaming, and error handling.",
        ),
      ),
      courseIds = List(
        "00000000-0000-0000-0000-000000000104",
        "00000000-0000-0000-0000-000000000105",
        "00000000-0000-0000-0000-000000000106",
      ).map(value => CourseId(UUID.fromString(value))),
      timeLabel = literal(TimeLabel("1 hour 52 minutes")),
      level     = CourseLevel.INTERMEDIATE,
      tone      = LearningPathTone.PURPLE,
    ),
  )

  val topics: Vector[String] = Vector(
    "All topics",
    "Effects & Concurrency",
    "Typelevel Community",
    "Streaming",
    "Error Handling",
  )

  private val coursesBySlug = courses.map(view => view.course.slug.value -> view).toMap
  private val coursesById   = courses.map(view => view.course.id.value.toString -> view).toMap

  def getCourse(slug: String): Option[CourseView]   = coursesBySlug.get(slug)
  def getCourseById(id: String): Option[CourseView] = coursesById.get(id)

  val validationErrors: Vector[String] =
    val duplicateSlugs = courses.groupBy(_.course.slug.value).collect {
      case (slug, values) if values.size > 1 =>
        s"Duplicate course slug: $slug"
    }
    val invalidPaths = learningPaths.flatMap { path =>
      path.courseIds.collect {
        case courseId if !coursesById.contains(courseId.value.toString) =>
          s"Learning path ${path.id.value} references missing course ${courseId.value}"
      }
    }
    (duplicateSlugs ++ invalidPaths).toVector
