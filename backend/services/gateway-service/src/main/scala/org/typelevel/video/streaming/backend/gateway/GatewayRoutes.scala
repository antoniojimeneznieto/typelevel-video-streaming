package org.typelevel.video.streaming.backend.gateway

import cats.effect.IO
import org.http4s.client.Client
import org.http4s.headers.Host
import org.http4s.server.Router
import org.http4s.{HttpApp, HttpRoutes, Uri}

object GatewayRoutes:

  def apply(client: Client[IO], config: UpstreamConfig): HttpApp[IO] =
    Router(
      "/api/identity" -> proxy(client, config.identity),
      "/api/catalog" -> proxy(client, config.catalog),
      "/api/playback" -> proxy(client, config.playback),
    ).orNotFound

  private def proxy(client: Client[IO], upstream: Uri): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case request =>
      val destination = upstream.copy(
        path  = upstream.path.dropEndsWithSlash.concat(request.pathInfo),
        query = request.uri.query,
      )

      client.toHttpApp.run(
        request.withUri(destination).removeHeader[Host],
      )
    }
