package org.typelevel.video.streaming.backend.runtime.http

import cats.effect.Async
import fs2.io.net.Network
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.HttpApp
import org.http4s.server.middleware.CORS
import org.typelevel.video.streaming.backend.runtime.config.HttpServerConfig

object HttpServer:

  def run[F[_]: Async: Network](config: HttpServerConfig, httpApp: HttpApp[F]): F[Unit] =
    EmberServerBuilder
      .default[F]
      .withHost(config.host)
      .withPort(config.port)
      .withHttpApp(CORS.policy.withAllowOriginAll(httpApp))
      .build
      .use(_ => Async[F].never[Unit])
