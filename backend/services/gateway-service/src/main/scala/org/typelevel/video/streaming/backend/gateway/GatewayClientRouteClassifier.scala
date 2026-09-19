package org.typelevel.video.streaming.backend.gateway

import org.http4s.Request
import org.http4s.otel4s.middleware.server.RouteClassifier
import org.typelevel.video.streaming.backend.catalog.api.CatalogService
import org.typelevel.video.streaming.backend.identity.api.IdentityService
import org.typelevel.video.streaming.backend.playback.api.PlaybackService
import org.typelevel.video.streaming.backend.runtime.http.SmithyRouteClassifier

/** Bounded outbound operation names derived from the configured upstreams and Smithy contracts. */
object GatewayClientRouteClassifier:

  def apply(config: UpstreamConfig): Request[?] => Option[String] =
    val upstreams = List(
      Upstream(
        "identity",
        config.identity,
        SmithyRouteClassifier.belowBasePath(config.identity.path.renderString, IdentityService),
      ),
      Upstream(
        "catalog",
        config.catalog,
        SmithyRouteClassifier.belowBasePath(config.catalog.path.renderString, CatalogService),
      ),
      Upstream(
        "playback",
        config.playback,
        SmithyRouteClassifier.belowBasePath(config.playback.path.renderString, PlaybackService),
      ),
    )

    request =>
      upstreams.collectFirst {
        case upstream if sameAuthority(request, upstream) =>
          upstream.routes.classify(request.requestPrelude).map(route => s"${upstream.name}:$route")
      }.flatten

  final private case class Upstream(
      name: String,
      uri: org.http4s.Uri,
      routes: RouteClassifier,
  )

  private def sameAuthority(request: Request[?], upstream: Upstream): Boolean =
    request.uri.scheme == upstream.uri.scheme && request.uri.authority == upstream.uri.authority
