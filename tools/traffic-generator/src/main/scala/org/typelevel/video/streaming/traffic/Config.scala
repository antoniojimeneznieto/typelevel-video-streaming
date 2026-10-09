package org.typelevel.video.streaming.traffic

import scala.concurrent.duration.*

import org.http4s.Uri

final case class Config(
    baseUrl: Uri                     = Uri.unsafeFromString("http://localhost:8085"),
    rate: Int                        = 5,
    duration: Option[FiniteDuration] = Some(3.minutes),
    maxConcurrent: Int               = 128,
    requestTimeout: FiniteDuration   = 30.seconds,
    drainTimeout: FiniteDuration     = 35.seconds,
    reportInterval: FiniteDuration   = 5.seconds,
    profile: TrafficProfile          = TrafficProfile.CatalogCourses,
    loginPercent: Int                = 10,
    modernPercent: Int               = 20,
    setupTimeout: FiniteDuration     = 60.seconds,
):
  require(setupTimeout > Duration.Zero, "setup-timeout must be positive")
  require(rate > 0 && rate <= 10000, "rate must be between 1 and 10000 requests/second")
  require(duration.forall(_ > Duration.Zero), "duration must be positive")
  require(maxConcurrent > 0, "max-concurrent must be positive")
  require(requestTimeout > Duration.Zero, "request-timeout must be positive")
  require(drainTimeout > Duration.Zero, "drain-timeout must be positive")
  require(reportInterval > Duration.Zero, "report-interval must be positive")
  require(loginPercent >= 0 && loginPercent <= 100, "login percent must be 0 to 100")
  require(Set(0, 20).contains(modernPercent), "modern percent must be 0 or 20")
  require(
    Config.isGatewayOrigin(baseUrl),
    "base-url must be an HTTP(S) gateway origin without credentials, path, query, or fragment",
  )

object Config:
  private[traffic] def isGatewayOrigin(uri: Uri): Boolean =
    uri.scheme.exists(s => s.value == "http" || s.value == "https") &&
      uri.authority.exists(a => a.userInfo.isEmpty) &&
      uri.query.isEmpty && uri.fragment.isEmpty &&
      (uri.path.isEmpty || uri.path == Uri.Path.Root)
