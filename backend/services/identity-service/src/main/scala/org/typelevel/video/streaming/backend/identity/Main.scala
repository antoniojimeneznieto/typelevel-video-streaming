package org.typelevel.video.streaming.backend.identity

import java.util.UUID

import cats.effect.{IO, IOApp}
import org.http4s.HttpApp
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import org.typelevel.video.streaming.backend.identity.api.IdentityService
import org.typelevel.video.streaming.backend.identity.auth.{TokenAudience, TokenIssuer}
import org.typelevel.video.streaming.backend.identity.config.AppConfig
import org.typelevel.video.streaming.backend.identity.repository.IdentityRepositoryImpl
import org.typelevel.video.streaming.backend.identity.service.{
  AccessTokenIssuerImpl,
  IdentityServiceImpl,
  PasswordHasherImpl,
}
import org.typelevel.video.streaming.backend.runtime.auth.{
  AccessTokenVerifier,
  BearerAuthenticationMiddleware,
  RsaKeyLoader,
}
import org.typelevel.video.streaming.backend.runtime.context.IOLocalRequestContext
import org.typelevel.video.streaming.backend.runtime.http.{HttpServer, SmithyRouteClassifier}
import org.typelevel.video.streaming.backend.runtime.postgres.Postgres
import org.typelevel.video.streaming.backend.runtime.telemetry.Telemetry
import smithy4s.http4s.SimpleRestJsonBuilder

object Main extends IOApp.Simple:

  override val run: IO[Unit] =
    Telemetry.resource("identity-service", runtime.metrics).use { otel =>
      given MeterProvider[IO]  = otel.meterProvider
      given TracerProvider[IO] = otel.tracerProvider

      AppConfig.load[IO].flatMap { config =>
        Postgres.sessionPool[IO](config.postgres).use { sessions =>
          for
            privateKey     <- RsaKeyLoader.privateKey(config.jwt.privateKeyPath)
            publicKey      <- RsaKeyLoader.publicKey(config.jwt.publicKeyPath)
            requestContext <- IOLocalRequestContext.create[UUID]
            repository      = new IdentityRepositoryImpl(sessions)
            passwordHasher  = PasswordHasherImpl()
            tokenIssuer     = AccessTokenIssuerImpl(
                            privateKey,
                            config.jwt.accessTokenExpiresIn,
                          )
            tokenVerifier = AccessTokenVerifier.userId(
                              publicKey,
                              TokenIssuer.IDENTITY.stringValue,
                              TokenAudience.COURSE_PLATFORM.stringValue,
                            )
            service       = new IdentityServiceImpl(
                        repository,
                        passwordHasher,
                        tokenIssuer,
                        requestContext,
                      )
            authentication = new BearerAuthenticationMiddleware(
                               tokenVerifier,
                               requestContext,
                             )
            _ <- SimpleRestJsonBuilder
                   .routes(service)
                   .middleware(authentication)
                   .resource
                   .use { identityRoutes =>
                     val app: HttpApp[IO] = identityRoutes.orNotFound
                     val routeClassifier  = SmithyRouteClassifier(IdentityService)

                     HttpServer.run(config.server, app, routeClassifier)
                   }
          yield ()
        }
      }
    }
