package org.typelevel.video.streaming.backend.identity

import cats.effect.{IO, IOApp}
import org.http4s.HttpApp
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import org.typelevel.video.streaming.backend.identity.auth.AccessTokenClaims
import org.typelevel.video.streaming.backend.identity.config.AppConfig
import org.typelevel.video.streaming.backend.identity.repository.IdentityRepositoryImpl
import org.typelevel.video.streaming.backend.identity.service.{
  AccessTokenIssuerImpl,
  AccessTokenVerifierImpl,
  IdentityServiceImpl,
  PasswordHasherImpl,
}
import org.typelevel.video.streaming.backend.runtime.auth.{
  BearerAuthenticationMiddleware,
  RsaKeyLoader,
}
import org.typelevel.video.streaming.backend.runtime.context.IOLocalRequestContext
import org.typelevel.video.streaming.backend.runtime.http.HttpServer
import org.typelevel.video.streaming.backend.runtime.postgres.Postgres
import org.typelevel.video.streaming.backend.runtime.telemetry.Telemetry
import smithy4s.http4s.SimpleRestJsonBuilder

object Main extends IOApp.Simple:

  override val run: IO[Unit] =
    Telemetry.resource("identity-service").use { otel =>
      given MeterProvider[IO]  = otel.meterProvider
      given TracerProvider[IO] = otel.tracerProvider

      AppConfig.load[IO].flatMap { config =>
        Postgres.sessionPool[IO](config.postgres).use { sessions =>
          for
            privateKey     <- RsaKeyLoader.privateKey(config.jwt.privateKeyPath)
            publicKey      <- RsaKeyLoader.publicKey(config.jwt.publicKeyPath)
            requestContext <- IOLocalRequestContext.create[AccessTokenClaims]
            repository      = new IdentityRepositoryImpl(sessions)
            passwordHasher  = PasswordHasherImpl()
            tokenIssuer     = AccessTokenIssuerImpl(
                            privateKey,
                            config.jwt.accessTokenExpiresIn,
                          )
            tokenVerifier = AccessTokenVerifierImpl(publicKey)
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

                     HttpServer.run(config.server, app)
                   }
          yield ()
        }
      }
    }
