package org.typelevel.video.streaming.backend.user

import java.time.Instant
import java.util.UUID

import cats.effect.{IO, Resource}
import org.http4s.client.Client
import org.http4s.HttpRoutes
import org.http4s.implicits.*
import smithy4s.http4s.SimpleRestJsonBuilder
import smithy4s.time.Timestamp
import weaver.SimpleIOSuite

object AccountRoutesSuite extends SimpleIOSuite:

  private val expectedProfile: Profile =
    Profile(
      id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
      username = "ada",
      createdAt = Timestamp.fromInstant(Instant.ofEpochSecond(1_700_000_000L)),
      email = Some("ada@example.com")
    )

  private val handler: AccountService[IO] =
    new AccountService[IO]:
      def getProfile(): IO[Profile] = IO.pure(expectedProfile)

  private val accountClient: Resource[IO, AccountService[IO]] =
    SimpleRestJsonBuilder
      .routes(handler)
      .resource
      .flatMap { (routes: HttpRoutes[IO]) =>
        SimpleRestJsonBuilder(AccountService)
          .client(Client.fromHttpApp(routes.orNotFound))
          .resource
      }

  test("GET /api/account/profile returns the caller's profile") {
    accountClient.use { account =>
      account.getProfile().map(profile => expect(profile == expectedProfile))
    }
  }
