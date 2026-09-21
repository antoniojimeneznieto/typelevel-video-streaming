package typelevel.courses.ui

import org.typelevel.video.streaming.backend.catalog.domain.Course

enum ArtworkVariant(val cssName: String):
  case Orbit extends ArtworkVariant("orbit")
  case Stream extends ArtworkVariant("stream")
  case Blocks extends ArtworkVariant("blocks")
  case Portal extends ArtworkVariant("portal")
  case Grid extends ArtworkVariant("grid")
  case Signal extends ArtworkVariant("signal")
  case Fold extends ArtworkVariant("fold")
  case Prism extends ArtworkVariant("prism")

final case class LessonView(
    id: String,
    title: String,
    durationSeconds: Int,
    description: String,
    preview: Boolean,
)

final case class ContentSource(name: String, url: String)

final case class CourseView(
    course: Course,
    eyebrow: String,
    shortDescription: String,
    isVideo: Boolean         = false,
    rating: Option[Double]   = None,
    students: Option[String] = None,
    featured: Boolean        = false,
    isNew: Boolean           = false,
    artwork: ArtworkVariant,
    artLabel: String,
    thumbnail: Option[String]     = None,
    source: Option[ContentSource] = None,
    outcomes: Vector[String]      = Vector.empty,
    prerequisites: Vector[String] = Vector.empty,
    lessons: Vector[LessonView]   = Vector.empty,
)
