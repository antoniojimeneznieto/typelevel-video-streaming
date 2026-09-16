package typelevel.courses.api

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobalScope

import org.http4s.Uri

final case class ApiConfig(
    identityBaseUrl: Uri,
    catalogBaseUrl: Uri,
    playbackBaseUrl: Uri,
)

object ApiConfig:
  lazy val browser: ApiConfig = ApiConfig(
    identityBaseUrl = Uri.unsafeFromString(ApiGlobals.`__IDENTITY_API_URL__`),
    catalogBaseUrl  = Uri.unsafeFromString(ApiGlobals.`__CATALOG_API_URL__`),
    playbackBaseUrl = Uri.unsafeFromString(ApiGlobals.`__PLAYBACK_API_URL__`),
  )

@js.native
@JSGlobalScope
private object ApiGlobals extends js.Object:
  val `__IDENTITY_API_URL__`: String = js.native
  val `__CATALOG_API_URL__`: String  = js.native
  val `__PLAYBACK_API_URL__`: String = js.native
