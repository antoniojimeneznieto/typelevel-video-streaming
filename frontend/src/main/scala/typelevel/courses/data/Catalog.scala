package typelevel.courses.data

import typelevel.courses.domain.*

object Catalog:
  private val defaultLessonDescription =
    "Follow along with a focused explanation, practical examples, and a small exercise."

  private def lessons(items: (String, String, Option[String])*): Vector[Lesson] =
    items.zipWithIndex.map { case ((title, duration, description), index) =>
      val durationParts   = duration.split(':').toList.flatMap(_.toIntOption)
      val durationSeconds = durationParts match
        case minutes :: seconds :: Nil => minutes * 60 + seconds
        case _ => 0

      Lesson(
        id              = s"lesson-${index + 1}",
        title           = title,
        duration        = duration,
        durationSeconds = durationSeconds,
        description     = description.getOrElse(defaultLessonDescription),
        preview         = index == 0
      )
    }.toVector

  private def lesson(
      title: String,
      duration: String,
      description: String
  ): (String, String, Option[String]) = (title, duration, Some(description))

  val courses: Vector[Course] = Vector(
    Course(
      id               = "00000000-0000-0000-0000-000000000104",
      slug             = "threads-at-scale",
      title            = "Threads at Scale",
      eyebrow          = "Community video",
      shortDescription =
        "Follow the path from hardware and JVM threads to asynchronous I/O and effect-system abstractions.",
      description =
        "Daniel Spiewak connects the hardware beneath our programs to JVM threads, asynchronous I/O, and high-level effect systems. The talk develops a practical model for reasoning about performance and improving tail latency in I/O-bound services.",
      level           = CourseLevel.Intermediate,
      format          = CourseFormat.Video,
      topic           = "Effects & Concurrency",
      technologies    = Vector("JVM", "Concurrency", "Cats Effect"),
      duration        = "30m 49s",
      durationSeconds = Some(1849),
      lessonCount     = 1,
      rating          = None,
      students        = None,
      year            = Some(2022),
      featured        = true,
      isNew           = false,
      artwork         = ArtworkVariant.Orbit,
      artLabel        = "JVM",
      thumbnail       = Some("/threads-at-scale-thumbnail.jpg"),
      source          = Some(
        ContentSource(
          "Sphere.it by VirtusLab on YouTube",
          "https://www.youtube.com/watch?v=PLApcas04V0"
        )
      ),
      instructor = Instructor("Daniel Spiewak", "Speaker · Sphere.it Conf 2022", "DS"),
      outcomes   = Vector(
        "Connect hardware execution constraints to the JVM thread model",
        "Explain where asynchronous I/O helps and where it does not",
        "Reason about tail latency in high-scale, I/O-bound services"
      ),
      prerequisites = Vector(
        "Basic familiarity with the JVM",
        "An interest in concurrency and service performance"
      ),
      lessons = lessons(
        lesson(
          "Threads at Scale",
          "30:49",
          "From raw hardware and JVM threads to asynchronous I/O, effect systems, and practical latency improvements."
        )
      )
    ),
    Course(
      id               = "00000000-0000-0000-0000-000000000101",
      slug             = "typelevel-retrospective",
      title            = "A Typelevel Retrospective",
      eyebrow          = "Community video",
      shortDescription =
        "Trace the growth of Typelevel’s community, libraries, runtimes, and tools with Arman Bilge.",
      description =
        "Arman Bilge looks back at the people and projects that shaped the modern Typelevel ecosystem. The talk covers cross-platform libraries, the evolution of the Cats Effect runtime, and the tools making functional Scala more approachable.",
      level           = CourseLevel.Beginner,
      format          = CourseFormat.Video,
      topic           = "Typelevel Community",
      technologies    = Vector("Typelevel", "Scala", "Cats Effect"),
      duration        = "35m 42s",
      durationSeconds = Some(2142),
      lessonCount     = 1,
      rating          = None,
      students        = None,
      year            = Some(2025),
      featured        = false,
      isNew           = false,
      artwork         = ArtworkVariant.Blocks,
      artLabel        = "TL",
      thumbnail       = Some("/typelevel-retrospective-thumbnail.jpg"),
      source          = Some(
        ContentSource(
          "Scala Days Conferences on YouTube",
          "https://www.youtube.com/watch?v=51kW8zK7YhQ"
        )
      ),
      instructor = Instructor("Arman Bilge", "Typelevel · Scala Days 2025", "AB"),
      outcomes   = Vector(
        "Understand how the Typelevel ecosystem and community evolved",
        "See how cross-platform support influenced the Cats Effect runtime",
        "Learn how newer tools are lowering the barrier to functional Scala"
      ),
      prerequisites = Vector("An interest in Scala and open-source communities"),
      lessons       = lessons(
        lesson(
          "A Typelevel Retrospective",
          "35:42",
          "A tour through Typelevel’s community growth, cross-platform ecosystem, runtime work, and efforts to make the stack easier to adopt."
        )
      )
    ),
    Course(
      id               = "00000000-0000-0000-0000-000000000105",
      slug             = "fs2-chunk",
      title            = "fs2.Chunk",
      eyebrow          = "Community video",
      shortDescription =
        "Look inside the data structure that powers FS2 and see how real constraints shaped its evolution.",
      description =
        "Michael Pilquist explores the design and evolution of fs2.Chunk, the data structure at the heart of FS2. The talk shows how practical performance and API constraints influence a foundational streaming abstraction.",
      level           = CourseLevel.Advanced,
      format          = CourseFormat.Video,
      topic           = "Streaming",
      technologies    = Vector("FS2", "Scala", "Functional Streaming"),
      duration        = "47m 54s",
      durationSeconds = Some(2874),
      lessonCount     = 1,
      rating          = None,
      students        = None,
      year            = Some(2022),
      featured        = false,
      isNew           = false,
      artwork         = ArtworkVariant.Stream,
      artLabel        = "CHUNK",
      thumbnail       = Some("/fs2-chunk-thumbnail.jpg"),
      source          = Some(
        ContentSource("Konfy on YouTube", "https://www.youtube.com/watch?v=wOybldcyMLs")
      ),
      instructor = Instructor("Michael Pilquist", "Speaker · Scala Love 2022", "MP"),
      outcomes   = Vector(
        "Understand the role Chunk plays inside FS2",
        "See how performance constraints shape data-structure design",
        "Recognize the trade-offs behind the evolution of the Chunk API"
      ),
      prerequisites = Vector("Scala fundamentals", "Some familiarity with FS2 streams"),
      lessons       = lessons(
        lesson(
          "fs2.Chunk",
          "47:54",
          "A close look at the structure that powers FS2, its evolution, and the constraints that shaped its design."
        )
      )
    ),
    Course(
      id               = "00000000-0000-0000-0000-000000000102",
      slug             = "cats-effect-3",
      title            = "Cats Effect 3",
      eyebrow          = "Community video",
      shortDescription =
        "Explore the ideas and execution model behind the third generation of Cats Effect.",
      description =
        "Daniel Spiewak presents Cats Effect 3 and develops a practical mental model for functional asynchronous and concurrent programs in Scala.",
      level           = CourseLevel.Intermediate,
      format          = CourseFormat.Video,
      topic           = "Effects & Concurrency",
      technologies    = Vector("Cats Effect", "Scala", "Concurrency"),
      duration        = "39m 30s",
      durationSeconds = Some(2370),
      lessonCount     = 1,
      rating          = None,
      students        = None,
      year            = Some(2021),
      featured        = false,
      isNew           = false,
      artwork         = ArtworkVariant.Grid,
      artLabel        = "CE3",
      thumbnail       = Some("/cats-effect-3-thumbnail.jpg"),
      source          = Some(
        ContentSource("Konfy on YouTube", "https://www.youtube.com/watch?v=JrpFFRdf7Q8")
      ),
      instructor = Instructor("Daniel Spiewak", "Speaker · Scala Love", "DS"),
      outcomes   = Vector(
        "Understand the core ideas behind Cats Effect 3",
        "Build a mental model for asynchronous and concurrent effects",
        "See how the runtime supports purely functional Scala programs"
      ),
      prerequisites = Vector("Scala fundamentals", "An introduction to functional effects"),
      lessons       = lessons(
        lesson(
          "Cats Effect 3",
          "39:30",
          "Daniel Spiewak introduces Cats Effect 3 and its model for functional asynchronous and concurrent programming."
        )
      )
    ),
    Course(
      id               = "00000000-0000-0000-0000-000000000106",
      slug             = "rethinking-monad-transformers",
      title            = "Rethinking Monad Transformers",
      eyebrow          = "Community video",
      shortDescription =
        "Explore a different way to carry typed errors through effectful Scala programs.",
      description =
        "Thanh Le presents the “submarine” technique for carrying arbitrary typed errors through an effect’s Throwable channel. The talk uses Scala 3 context functions, inline definitions, and implicit capabilities to rethink familiar monad-transformer trade-offs.",
      level           = CourseLevel.Advanced,
      format          = CourseFormat.Video,
      topic           = "Error Handling",
      technologies    = Vector("Scala 3", "Functional Programming", "Typed Errors"),
      duration        = "33m 27s",
      durationSeconds = Some(2007),
      lessonCount     = 1,
      rating          = None,
      students        = None,
      year            = Some(2025),
      featured        = false,
      isNew           = true,
      artwork         = ArtworkVariant.Fold,
      artLabel        = "E | A",
      thumbnail       = Some("/rethinking-monad-transformers-thumbnail.jpg"),
      source          = Some(
        ContentSource(
          "Scala Days Conferences on YouTube",
          "https://www.youtube.com/watch?v=nNqx2HiL7cc"
        )
      ),
      instructor = Instructor("Thanh Le", "Speaker · Scala Days 2025", "TL"),
      outcomes   = Vector(
        "Evaluate the trade-offs of conventional monad transformers",
        "Understand how the submarine technique transports typed errors",
        "See how Scala 3 language features support implicit error capabilities"
      ),
      prerequisites = Vector(
        "Scala 3 fundamentals",
        "Familiarity with monadic code and typed errors"
      ),
      lessons = lessons(
        lesson(
          "Rethinking Monad Transformers",
          "33:27",
          "A Scala 3 approach to carrying typed errors through an effect without a conventional transformer stack."
        )
      )
    )
  )

  val learningPaths: Vector[LearningPath] = Vector(
    LearningPath(
      id          = "discover-typelevel",
      title       = "Discover Typelevel",
      description = "Explore the ecosystem, its community, and its core ideas.",
      courseIds   = Vector(
        "00000000-0000-0000-0000-000000000101",
        "00000000-0000-0000-0000-000000000102"
      ),
      time  = "1 hour 15 minutes",
      level = CourseLevel.Beginner,
      tone  = PathTone.Yellow
    ),
    LearningPath(
      id          = "inside-typelevel",
      title       = "Inside Typelevel",
      description =
        "Explore the design decisions behind effects, concurrency, streaming, and error handling.",
      courseIds = Vector(
        "00000000-0000-0000-0000-000000000104",
        "00000000-0000-0000-0000-000000000105",
        "00000000-0000-0000-0000-000000000106"
      ),
      time  = "1 hour 52 minutes",
      level = CourseLevel.Intermediate,
      tone  = PathTone.Purple
    )
  )

  val topics: Vector[String] = Vector(
    "All topics",
    "Effects & Concurrency",
    "Typelevel Community",
    "Streaming",
    "Error Handling"
  )

  private val coursesBySlug = courses.map(course => course.slug -> course).toMap
  private val coursesById   = courses.map(course => course.id -> course).toMap

  def getCourse(slug: String): Option[Course]   = coursesBySlug.get(slug)
  def getCourseById(id: String): Option[Course] = coursesById.get(id)

  val validationErrors: Vector[String] =
    val duplicateSlugs = courses.groupBy(_.slug).collect {
      case (slug, values) if values.size > 1 =>
        s"Duplicate course slug: $slug"
    }
    val invalidPaths = learningPaths.flatMap { path =>
      path.courseIds.collect {
        case courseId if !coursesById.contains(courseId) =>
          s"Learning path ${path.id} references missing course $courseId"
      }
    }
    (duplicateSlugs ++ invalidPaths).toVector
