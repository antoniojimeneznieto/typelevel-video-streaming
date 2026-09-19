package org.typelevel.video.streaming.backend.gateway

import org.http4s.{Request, Uri}
import org.http4s.otel4s.middleware.client.UriTemplateClassifier
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

  def urlTemplates(config: UpstreamConfig): UriTemplateClassifier =
    val upstreams = List(
      UrlTemplateUpstream(
        config.identity,
        SmithyRouteClassifier
          .urlTemplatesBelowBasePath(config.identity.path.renderString, IdentityService),
      ),
      UrlTemplateUpstream(
        config.catalog,
        SmithyRouteClassifier
          .urlTemplatesBelowBasePath(config.catalog.path.renderString, CatalogService),
      ),
      UrlTemplateUpstream(
        config.playback,
        SmithyRouteClassifier.urlTemplatesBelowBasePath(
          config.playback.path.renderString,
          PlaybackService,
        ),
      ),
    )

    new UriTemplateClassifier:
      override def classify(uri: Uri): Option[String] =
        upstreams.collectFirst {
          case upstream if sameAuthority(uri, upstream.uri) => upstream.templates.classify(uri)
        }.flatten

  final private case class Upstream(
      name: String,
      uri: Uri,
      routes: RouteClassifier,
  )

  final private case class UrlTemplateUpstream(
      uri: Uri,
      templates: UriTemplateClassifier,
  )

  private def sameAuthority(request: Request[?], upstream: Upstream): Boolean =
    sameAuthority(request.uri, upstream.uri)

  private def sameAuthority(left: Uri, right: Uri): Boolean =
    left.scheme == right.scheme && left.authority == right.authority
