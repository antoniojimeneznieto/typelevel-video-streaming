package org.typelevel.video.streaming.backend.gateway

import org.http4s.otel4s.middleware.server.RouteClassifier
import org.typelevel.video.streaming.backend.catalog.api.CatalogService
import org.typelevel.video.streaming.backend.identity.api.IdentityService
import org.typelevel.video.streaming.backend.playback.api.PlaybackService
import org.typelevel.video.streaming.backend.runtime.http.SmithyRouteClassifier

/** Stable public HTTP operation names derived from the downstream Smithy contracts. */
object GatewayRouteClassifier:

  val routes: RouteClassifier =
    SmithyRouteClassifier.firstMatch(
      SmithyRouteClassifier.mounted("/api/identity", IdentityService),
      SmithyRouteClassifier.mounted("/api/catalog", CatalogService),
      SmithyRouteClassifier.mounted("/api/playback", PlaybackService),
    )
