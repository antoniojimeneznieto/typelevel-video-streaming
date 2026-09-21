package typelevel.courses.ui

import org.typelevel.video.streaming.backend.catalog.domain.{
  CourseKind,
  CourseLevel,
  Instructor,
  LearningPathTone,
}

object CatalogPresentation:
  extension (level: CourseLevel)
    def label: String = level match
      case CourseLevel.BEGINNER => "Beginner"
      case CourseLevel.INTERMEDIATE => "Intermediate"
      case CourseLevel.ADVANCED => "Advanced"

  extension (kind: CourseKind)
    def label: String = kind match
      case CourseKind.COURSE => "Course"
      case CourseKind.WORKSHOP => "Workshop"
      case CourseKind.TALK => "Talk"

  extension (tone: LearningPathTone) def cssName: String = tone.stringValue

  extension (instructor: Instructor)
    def initials: String =
      val result = instructor.name.value.trim
        .split("\\s+")
        .take(2)
        .flatMap(_.headOption)
        .mkString
        .toUpperCase
      if result.isEmpty then "?" else result

  extension (view: CourseView)
    def formatLabel: String = if view.isVideo then "Video" else view.course.kind.label

    def lessonCount: Int = view.course.lessonCount.fold(0)(_.value)

    def duration: String = view.course.durationSeconds.fold("Duration coming soon") { duration =>
      val seconds = duration.value
      if view.isVideo then
        val minutes   = seconds / 60
        val remaining = seconds % 60
        if remaining == 0 then s"${minutes}m" else s"${minutes}m ${remaining}s"
      else
        val totalMinutes = math.round(seconds.toDouble / 60).toInt
        val hours        = totalMinutes / 60
        val minutes      = totalMinutes % 60
        if hours == 0 then s"${minutes}m"
        else if minutes == 0 then s"${hours}h"
        else s"${hours}h ${minutes}m"
    }

  extension (lesson: LessonView)
    def duration: String = f"${lesson.durationSeconds / 60}:${lesson.durationSeconds % 60}%02d"
