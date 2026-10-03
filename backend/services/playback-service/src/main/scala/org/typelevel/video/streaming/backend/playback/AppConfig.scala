package org.typelevel.video.streaming.backend.playback.config

import java.net.URI
import java.nio.file.{Path, Paths}
import scala.util.Try

import cats.effect.Async
import cats.syntax.all.*
import ciris.{ConfigDecoder, ConfigValue, Effect, env}
import com.comcast.ip4s.port
import org.typelevel.video.streaming.backend.playback.domain.ExpiresInSeconds
import org.typelevel.video.streaming.backend.runtime.config.{HttpServerConfig, PostgresConfig}

final case class S3Config(
    endpoint: Option[URI],
    publicEndpoint: Option[URI],
    region: String,
    bucket: String,
    pathStyleAccessEnabled: Boolean,
    urlExpiresIn: ExpiresInSeconds,
)

final case class JwtConfig(publicKeyPath: Path)

final case class KafkaConfig(
    bootstrapServers: String,
    groupId: String,
    lessonPublishedTopic: String,
    userCreatedTopic: String,
)

final case class AppConfig(
    server: HttpServerConfig,
    s3: S3Config,
    jwt: JwtConfig,
    postgres: PostgresConfig,
    kafka: KafkaConfig,
    workshopReadMode: Boolean,
)

object AppConfig:

  private val endpointDecoder: ConfigDecoder[String, URI] =
    ConfigDecoder[String]
      .mapOption(
        "HTTP(S) endpoint without credentials, path, query or fragment",
      ) { value =>
        Try(URI.create(value)).toOption.filter { uri =>
          Set("http", "https").contains(uri.getScheme) &&
          Option(uri.getHost).exists(_.nonEmpty) &&
          uri.getUserInfo == null && uri.getQuery == null && uri.getFragment == null &&
          (uri.getPath.isEmpty || uri.getPath == "/") &&
          (uri.getPort == -1 || (uri.getPort > 0 && uri.getPort <= 65535))
        }
      }
      .redacted

  private given ConfigDecoder[String, URI] = endpointDecoder

  private given ConfigDecoder[String, Path] =
    ConfigDecoder[String].mapOption("Path") { value =>
      Option.when(value.nonEmpty)(value).flatMap(path => Try(Paths.get(path)).toOption)
    }

  private val expiresInDecoder: ConfigDecoder[String, ExpiresInSeconds] =
    ConfigDecoder[String, Int].mapOption("URL lifetime between 1 and 3600 seconds") { value =>
      Option.when(value <= 3600)(value).flatMap(ExpiresInSeconds(_).toOption)
    }

  private given ConfigDecoder[String, ExpiresInSeconds] = expiresInDecoder

  private val defaultUrlLifetime =
    ExpiresInSeconds(900).fold(message => throw new IllegalStateException(message), identity)

  private val s3Config: ConfigValue[Effect, S3Config] =
    (
      env("S3_ENDPOINT").as[URI].option,
      env("S3_PUBLIC_ENDPOINT").as[URI].option,
      env("AWS_REGION").default("us-east-1"),
      env("S3_BUCKET")
        .default("videos")
        .as(using
          ConfigDecoder[String].mapOption("S3 bucket name") { value =>
            Option.when(value.matches("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$"))(value)
          },
        ),
      env("S3_PATH_STYLE_ACCESS_ENABLED").as[Boolean].default(true),
      env("S3_URL_EXPIRES_IN_SECONDS").as[ExpiresInSeconds].default(defaultUrlLifetime),
    ).parMapN(S3Config.apply)

  private val jwtConfig: ConfigValue[Effect, JwtConfig] =
    env("JWT_PUBLIC_KEY_PATH")
      .as[Path]
      .default(Paths.get("infrastructure/identity/keys/public-key.pem"))
      .map(JwtConfig.apply)

  private val nonEmptyString: ConfigDecoder[String, String] =
    ConfigDecoder[String].mapOption("Non-empty value")(value =>
      Option.when(value.trim.nonEmpty)(value),
    )

  private val kafkaConfig: ConfigValue[Effect, KafkaConfig] =
    (
      env("KAFKA_BOOTSTRAP_SERVERS").default("localhost:9092").as(using nonEmptyString),
      env("KAFKA_GROUP_ID").default("playback-projections-v1").as(using nonEmptyString),
      env("KAFKA_LESSON_PUBLISHED_TOPIC")
        .default("catalog.lesson-published.v1")
        .as(using nonEmptyString),
      env("KAFKA_USER_CREATED_TOPIC").default("identity.user-created.v1").as(using nonEmptyString),
    ).parMapN(KafkaConfig.apply).flatMap { config =>
      if config.lessonPublishedTopic == config.userCreatedTopic then
        ConfigValue.failed(ciris.ConfigError("Kafka event topics must be distinct"))
      else ConfigValue.default(config)
    }

  def load[F[_]: Async]: F[AppConfig] =
    (
      HttpServerConfig.config(port"8083"),
      s3Config,
      jwtConfig,
      PostgresConfig.config("playback", "playback-local-secret"),
      kafkaConfig,
      env("WORKSHOP_READ_MODE").as[Boolean].default(false),
    ).parMapN(AppConfig.apply).load[F]
