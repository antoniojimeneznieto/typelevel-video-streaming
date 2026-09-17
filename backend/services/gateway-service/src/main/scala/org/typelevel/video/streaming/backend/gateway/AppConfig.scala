package org.typelevel.video.streaming.backend.gateway

import cats.effect.Async
import cats.syntax.all.*
import ciris.{ConfigDecoder, ConfigValue, Effect, env}
import com.comcast.ip4s.port
import org.http4s.implicits.uri
import org.http4s.Uri
import org.typelevel.video.streaming.backend.runtime.config.HttpServerConfig

final case class UpstreamConfig(
    identity: Uri,
    catalog: Uri,
    playback: Uri,
)

final case class AppConfig(
    server: HttpServerConfig,
    upstreams: UpstreamConfig,
)

object AppConfig:

  private given ConfigDecoder[String, Uri] =
    ConfigDecoder[String]
      .mapOption("absolute HTTP(S) URI without credentials, query, or fragment") { value =>
        Uri.fromString(value).toOption.filter { uri =>
          uri.scheme.exists(scheme => Set("http", "https").contains(scheme.value)) &&
          uri.host.exists(_.value.nonEmpty) && uri.userInfo.isEmpty && uri.query.isEmpty &&
          uri.fragment.isEmpty
        }
      }
      .redacted

  private val upstreamConfig: ConfigValue[Effect, UpstreamConfig] =
    (
      env("IDENTITY_BASE_URL").as[Uri].default(uri"http://localhost:8081"),
      env("CATALOG_BASE_URL").as[Uri].default(uri"http://localhost:8082"),
      env("PLAYBACK_BASE_URL").as[Uri].default(uri"http://localhost:8083"),
    ).parMapN(UpstreamConfig.apply)

  def load[F[_]: Async]: F[AppConfig] =
    (
      HttpServerConfig.config(port"8084"),
      upstreamConfig,
    ).parMapN(AppConfig.apply).load[F]
