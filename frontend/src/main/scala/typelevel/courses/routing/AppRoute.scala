package typelevel.courses.routing

import org.http4s.syntax.all.*
import org.http4s.Uri

enum AppRoute:
  case Landing
  case Login(returnTo: Option[String] = None)
  case Register
  case Browse
  case Search
  case Paths
  case MyLearning
  case Course(slug: String)
  case Watch(slug: String, lessonId: String)
  case NotFound

  def uri: Uri = this match
    case Landing => uri"/"
    case Login(None) => uri"/login"
    case Login(Some(returnTo)) => uri"/login".withQueryParam("from", returnTo)
    case Register => uri"/register"
    case Browse => uri"/browse"
    case Search => uri"/search"
    case Paths => uri"/paths"
    case MyLearning => uri"/my-learning"
    case Course(slug) => Uri.unsafeFromString(s"/course/$slug")
    case Watch(slug, lessonId) => Uri.unsafeFromString(s"/watch/$slug/$lessonId")
    case NotFound => uri"/404"

object AppRoute:
  def parse(uri: Uri): AppRoute =
    val path  = normalizedPath(uri)
    val parts = path.split('/').iterator.filter(_.nonEmpty).toList
    parts match
      case Nil => Landing
      case "login" :: Nil => Login(uri.query.params.get("from").filter(isSafeReturnPath))
      case "register" :: Nil => Register
      case "browse" :: Nil => Browse
      case "search" :: Nil => Search
      case "paths" :: Nil => Paths
      case "my-learning" :: Nil => MyLearning
      case "course" :: slug :: Nil => Course(slug)
      case "content" :: slug :: Nil => Course(slug)
      case "watch" :: slug :: Nil => Watch(slug, "lesson-1")
      case "watch" :: slug :: lesson :: Nil => Watch(slug, lesson)
      case _ => NotFound

  def normalizedPath(uri: Uri): String =
    val rendered = uri.path.renderString
    if rendered.isEmpty then "/" else rendered

  def isProtected(uri: Uri): Boolean =
    parse(uri) match
      case Browse | Search | Paths | MyLearning | Course(_) | Watch(_, _) => true
      case _ => false

  def safeReturnPath(uri: Uri): String =
    val path  = normalizedPath(uri)
    val query = uri.query.renderString match
      case "" => ""
      case value => s"?$value"
    val fragment = uri.fragment.fold("")(value => s"#$value")
    s"$path$query$fragment"

  def isSafeReturnPath(value: String): Boolean =
    value.startsWith("/") && !value.startsWith("//")
