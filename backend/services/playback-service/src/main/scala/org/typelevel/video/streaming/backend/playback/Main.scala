package org.typelevel.video.streaming.backend.playback

import java.util.UUID

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all.*
import org.http4s.Header
import org.typelevel.ci.CIString
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.video.streaming.backend.playback.config.AppConfig
import org.typelevel.video.streaming.backend.playback.repository.{
  PlaybackProjectionRepositoryImpl,
  PlaybackRepositoryImpl
}
import org.typelevel.video.streaming.backend.playback.service.PlaybackServiceImpl
import org.typelevel.video.streaming.backend.playback.storage.S3VideoStorageImpl
import org.typelevel.video.streaming.backend.playback.worker.PlaybackEventWorker
import org.typelevel.video.streaming.backend.runtime.auth.{
  AccessTokenVerifier,
  BearerAuthenticationMiddleware,
  RsaKeyLoader
}
import org.typelevel.video.streaming.backend.runtime.context.IOLocalRequestContext
import org.typelevel.video.streaming.backend.runtime.http.HttpServer
import org.typelevel.video.streaming.backend.runtime.postgres.Postgres
import smithy4s.http4s.SimpleRestJsonBuilder

object Main extends IOApp.Simple:

  private given Meter[IO]  = Meter.noop[IO]
  private given Tracer[IO] = Tracer.noop[IO]

  override val run: IO[Unit] =
    AppConfig.load[IO].flatMap { config =>
      val resources = for
        publicKey     <- Resource.eval(RsaKeyLoader.publicKey(config.jwt.publicKeyPath))
        verifier       = AccessTokenVerifier.userId(publicKey, "identity", "course-platform")
        context       <- Resource.eval(IOLocalRequestContext.create[UUID])
        storage       <- S3VideoStorageImpl.resource(config.s3)
        sessions      <- Postgres.sessionPool[IO](config.postgres)
        projections    = new PlaybackProjectionRepositoryImpl(sessions)
        repository     = new PlaybackRepositoryImpl(sessions)
        worker         = new PlaybackEventWorker(config.kafka, projections)
        service        = new PlaybackServiceImpl(repository, storage, context)
        authentication = new BearerAuthenticationMiddleware(verifier, context)
        routes        <- SimpleRestJsonBuilder.routes(service).middleware(authentication).resource
      yield (routes, worker)

      resources.use { case (routes, worker) =>
        val app = routes.orNotFound.map(
          _.putHeaders(Header.Raw(CIString("Cache-Control"), "no-store"))
        )

        (HttpServer.run(config.server, app), worker.run).parTupled.void
      }
    }
