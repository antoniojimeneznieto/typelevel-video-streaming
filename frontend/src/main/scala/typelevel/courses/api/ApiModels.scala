package typelevel.courses.api

import io.circe.{Decoder, Encoder}

enum UserRole(val value: String):
  case Student extends UserRole("student")
  case Admin extends UserRole("admin")

object UserRole:
  given Decoder[UserRole] = enumDecoder(values, _.value, "role")

final case class User(id: String, email: String, displayName: String, role: UserRole)
    derives Decoder

final case class RegisterRequest(email: String, password: String, displayName: String)
    derives Encoder

final case class LoginRequest(email: String, password: String) derives Encoder

enum TokenType(val value: String):
  case Bearer extends TokenType("Bearer")

object TokenType:
  given Decoder[TokenType] = enumDecoder(values, _.value, "token type")

final case class LoginResponse(accessToken: String, tokenType: TokenType, expiresIn: Int)
    derives Decoder

enum ApiCourseLevel(val value: String):
  case Beginner extends ApiCourseLevel("beginner")
  case Intermediate extends ApiCourseLevel("intermediate")
  case Advanced extends ApiCourseLevel("advanced")

object ApiCourseLevel:
  given Decoder[ApiCourseLevel] = enumDecoder(values, _.value, "course level")

enum ApiCourseKind(val value: String):
  case Course extends ApiCourseKind("course")
  case Workshop extends ApiCourseKind("workshop")
  case Talk extends ApiCourseKind("talk")

object ApiCourseKind:
  given Decoder[ApiCourseKind] = enumDecoder(values, _.value, "course kind")

enum ApiLearningPathTone(val value: String):
  case Yellow extends ApiLearningPathTone("yellow")
  case Purple extends ApiLearningPathTone("purple")
  case Coral extends ApiLearningPathTone("coral")

object ApiLearningPathTone:
  given Decoder[ApiLearningPathTone] = enumDecoder(values, _.value, "learning path tone")

final case class ApiInstructor(name: String, role: Option[String]) derives Decoder

final case class ApiCourse(
    id: String,
    slug: String,
    title: String,
    description: String,
    level: ApiCourseLevel,
    kind: ApiCourseKind,
    topic: String,
    technologies: Vector[String],
    instructor: ApiInstructor,
    durationSeconds: Option[Int],
    lessonCount: Option[Int]
) derives Decoder

final case class ApiLearningPath(
    id: String,
    title: String,
    description: String,
    timeLabel: String,
    level: ApiCourseLevel,
    tone: ApiLearningPathTone,
    courseIds: Vector[String]
) derives Decoder

final case class Page[A](items: Vector[A], total: Long, limit: Int, offset: Int) derives Decoder

final case class ListCoursesParams(
    query: Option[String]         = None,
    level: Option[ApiCourseLevel] = None,
    kind: Option[ApiCourseKind]   = None,
    topic: Option[String]         = None,
    technology: Option[String]    = None,
    limit: Option[Int]            = None,
    offset: Option[Int]           = None
)

final case class ListLearningPathsParams(
    query: Option[String]             = None,
    level: Option[ApiCourseLevel]     = None,
    tone: Option[ApiLearningPathTone] = None,
    limit: Option[Int]                = None,
    offset: Option[Int]               = None
)

final case class PlaybackUrlResponse(url: String, expiresIn: Int) derives Decoder

final case class PlaybackProgress(
    courseId: String,
    lessonId: String,
    positionSeconds: Int,
    completed: Boolean,
    updatedAt: String
) derives Decoder

final case class Favorite(courseId: String, createdAt: String) derives Decoder

final case class ProgressQuery(
    courseId: Option[String]   = None,
    completed: Option[Boolean] = None,
    limit: Option[Int]         = None,
    offset: Option[Int]        = None
)

final case class FavoritesQuery(limit: Option[Int] = None, offset: Option[Int] = None)

final case class PositionRequest(positionSeconds: Int) derives Encoder

private def enumDecoder[A](
    values: Array[A],
    value: A => String,
    label: String
): Decoder[A] = Decoder.decodeString.emap { candidate =>
  values.find(item => value(item) == candidate).toRight(s"Unknown $label: $candidate")
}
