package typelevel.courses.domain

enum CourseLevel(val label: String):
  case Beginner extends CourseLevel("Beginner")
  case Intermediate extends CourseLevel("Intermediate")
  case Advanced extends CourseLevel("Advanced")

object CourseLevel:
  def fromLabel(value: String): Option[CourseLevel] = values.find(_.label == value)

enum CourseFormat(val label: String):
  case Course extends CourseFormat("Course")
  case Workshop extends CourseFormat("Workshop")
  case Talk extends CourseFormat("Talk")
  case Video extends CourseFormat("Video")

object CourseFormat:
  def fromLabel(value: String): Option[CourseFormat] = values.find(_.label == value)

enum ArtworkVariant(val cssName: String):
  case Orbit extends ArtworkVariant("orbit")
  case Stream extends ArtworkVariant("stream")
  case Blocks extends ArtworkVariant("blocks")
  case Portal extends ArtworkVariant("portal")
  case Grid extends ArtworkVariant("grid")
  case Signal extends ArtworkVariant("signal")
  case Fold extends ArtworkVariant("fold")
  case Prism extends ArtworkVariant("prism")

enum PathTone(val cssName: String):
  case Purple extends PathTone("purple")
  case Coral extends PathTone("coral")
  case Yellow extends PathTone("yellow")

final case class Lesson(
    id: String,
    title: String,
    duration: String,
    durationSeconds: Int,
    description: String,
    preview: Boolean,
)

final case class Instructor(name: String, role: String, initials: String)

final case class ContentSource(name: String, url: String)

final case class Course(
    id: String,
    slug: String,
    title: String,
    eyebrow: String,
    shortDescription: String,
    description: String,
    level: CourseLevel,
    format: CourseFormat,
    topic: String,
    technologies: Vector[String],
    duration: String,
    durationSeconds: Option[Int],
    lessonCount: Int,
    rating: Option[Double],
    students: Option[String],
    year: Option[Int],
    featured: Boolean,
    isNew: Boolean,
    artwork: ArtworkVariant,
    artLabel: String,
    thumbnail: Option[String],
    source: Option[ContentSource],
    instructor: Instructor,
    outcomes: Vector[String],
    prerequisites: Vector[String],
    lessons: Vector[Lesson],
)

final case class LearningPath(
    id: String,
    title: String,
    description: String,
    courseIds: Vector[String],
    time: String,
    level: CourseLevel,
    tone: PathTone,
)
