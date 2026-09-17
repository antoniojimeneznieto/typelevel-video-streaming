package org.typelevel.video.streaming.backend.gateway

import cats.effect.{IO, Resource}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.otel4s.middleware.trace.client.*
import org.http4s.{Query, Uri}
import org.typelevel.otel4s.trace.TracerProvider

private[gateway] object GatewayClient:

  def resource(using TracerProvider[IO]): Resource[IO, Client[IO]] =
    EmberClientBuilder.default[IO].build.evalMap(instrument)

  def instrument(client: Client[IO])(using TracerProvider[IO]): IO[Client[IO]] =
    ClientMiddleware
      .builder[IO](ClientSpanDataProvider.openTelemetry(redactor))
      .build
      .map(_.wrapClient(client))

  private val redactor: UriRedactor = new UriRedactor:
    override def redactAuthority(authority: Uri.Authority): Option[Uri.Authority] =
      Some(authority.copy(userInfo = None))

    override def redactPath(path: Uri.Path): Uri.Path = Uri.Path.empty

    override def redactQuery(query: Query): Query = Query.empty

    override def redactFragment(fragment: String): Option[String] = None
