package org.typelevel.video.streaming.backend.user

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import org.flywaydb.core.Flyway
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.HttpApp
import org.http4s.server.middleware.CORS
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.video.streaming.backend.common.auth.{
  AuthMiddleware,
  CallerContext,
  TokenVerifier
}
import org.typelevel.video.streaming.backend.common.config.PostgresConfig
import org.typelevel.video.streaming.backend.user.config.AppConfig
import org.typelevel.video.streaming.backend.user.repository.UserProfileRepository
import org.typelevel.video.streaming.backend.user.service.AccountServiceImpl
import skunk.Session
import smithy4s.http4s.SimpleRestJsonBuilder

object Main extends IOApp.Simple:

  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private given Meter[IO] = Meter.Implicits.noop
  private given Tracer[IO] = Tracer.Implicits.noop

  val run: IO[Unit] =
    AppConfig.load.flatMap { config =>
      Session
        .Builder[IO]
        .withHost(config.postgres.host)
        .withPort(config.postgres.port)
        .withUserAndPassword(config.postgres.user, config.postgres.password)
        .withDatabase(config.postgres.database)
        .pooled(config.postgres.maxConnections)
        .use { pool =>
          for
            _ <- migrate(config.postgres)
            _ <- Logger[IO].info(s"Starting user-service on port ${config.server.port}")
            callerContext <- CallerContext.make
            verifier = TokenVerifier.make(config.keycloak)
            handler = AccountServiceImpl(pool, new UserProfileRepository, callerContext)
            _ <- serve(config, handler, verifier, callerContext)
          yield ()
        }
    }

  private def migrate(postgres: PostgresConfig): IO[Unit] =
    IO.blocking {
      Flyway
        .configure()
        .dataSource(
          s"jdbc:postgresql://${postgres.host}:${postgres.port}/${postgres.database}",
          postgres.user,
          postgres.password
        )
        .load()
        .migrate()
    } *> Logger[IO].info("Database migrations applied")

  private def serve(
      config: AppConfig,
      handler: AccountServiceImpl,
      verifier: TokenVerifier,
      callerContext: CallerContext
  ): IO[Nothing] =
    SimpleRestJsonBuilder
      .routes(handler)
      .resource
      .flatMap { apiRoutes =>
        val authed = AuthMiddleware(verifier, callerContext).apply(apiRoutes)
        val docs = smithy4s.http4s.swagger.docs[IO](AccountService)
        val app: HttpApp[IO] =
          CORS.policy.withAllowOriginAll.apply((docs <+> authed).orNotFound)

        EmberServerBuilder
          .default[IO]
          .withHost(config.server.host)
          .withPort(config.server.port)
          .withHttpApp(app)
          .build
      }
      .useForever
