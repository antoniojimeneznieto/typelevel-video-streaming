package org.typelevel.video.streaming.backend.playback.storage

import java.time.Duration

import cats.effect.{IO, Resource}
import org.typelevel.video.streaming.backend.playback.api.{
  PlaybackUnavailableError,
  PlaybackUrlResponse,
  VideoNotFoundError
}
import org.typelevel.video.streaming.backend.playback.config.S3Config
import org.typelevel.video.streaming.backend.playback.domain.{ObjectKey, PlaybackUrl}
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  HeadObjectRequest,
  NoSuchBucketException,
  S3Exception
}
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}

trait S3VideoStorage:
  def getPlaybackUrl(objectKey: ObjectKey): IO[PlaybackUrlResponse]

final class S3VideoStorageImpl(client: S3Client, presigner: S3Presigner, config: S3Config)
    extends S3VideoStorage:

  override def getPlaybackUrl(objectKey: ObjectKey): IO[PlaybackUrlResponse] =
    val head = HeadObjectRequest.builder().bucket(config.bucket).key(objectKey.value).build()
    val get  = GetObjectRequest
      .builder()
      .bucket(config.bucket)
      .key(objectKey.value)
      .responseContentType("video/mp4")
      .responseContentDisposition("inline")
      .build()
    val request = GetObjectPresignRequest
      .builder()
      .getObjectRequest(get)
      .signatureDuration(Duration.ofSeconds(config.urlExpiresIn.value.toLong))
      .build()

    (IO.blocking(client.headObject(head)) *> IO.blocking {
      val signed = presigner.presignGetObject(request)
      if !signed.isBrowserExecutable then throw unavailable
      PlaybackUrl(signed.url().toExternalForm) match
        case Right(url) => PlaybackUrlResponse(url, config.urlExpiresIn)
        case Left(_) => throw unavailable
    }).handleErrorWith {
      case error: S3Exception if isMissingObject(error) =>
        IO.raiseError(VideoNotFoundError("Video not found"))
      case _: SdkException => IO.raiseError(unavailable)
      case error => IO.raiseError(error)
    }

  private def isMissingObject(error: S3Exception): Boolean =
    error.statusCode() == 404 &&
      !error.isInstanceOf[NoSuchBucketException] &&
      !Option(error.awsErrorDetails()).exists(_.errorCode() == "NoSuchBucket")

  private def unavailable: PlaybackUnavailableError =
    PlaybackUnavailableError("Video storage is temporarily unavailable")

object S3VideoStorageImpl:

  def resource(config: S3Config): Resource[IO, S3VideoStorage] =
    val serviceConfiguration = S3Configuration
      .builder()
      .pathStyleAccessEnabled(config.pathStyleAccessEnabled)
      .build()

    for
      credentials <-
        Resource.fromAutoCloseable(IO.blocking(DefaultCredentialsProvider.builder().build()))
      client <- Resource.fromAutoCloseable(IO.blocking {
                  val builder = S3Client
                    .builder()
                    .credentialsProvider(credentials)
                    .region(Region.of(config.region))
                    .serviceConfiguration(serviceConfiguration)
                    .httpClientBuilder(
                      UrlConnectionHttpClient
                        .builder()
                        .connectionTimeout(Duration.ofSeconds(3))
                        .socketTimeout(Duration.ofSeconds(5))
                    )
                    .overrideConfiguration(
                      ClientOverrideConfiguration
                        .builder()
                        .apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(5))
                        .build()
                    )
                  config.endpoint.foreach(builder.endpointOverride)
                  builder.build()
                })
      presigner <- Resource.fromAutoCloseable(IO.blocking {
                     val builder = S3Presigner
                       .builder()
                       .credentialsProvider(credentials)
                       .region(Region.of(config.region))
                       .serviceConfiguration(serviceConfiguration)
                     config.publicEndpoint.orElse(config.endpoint).foreach(builder.endpointOverride)
                     builder.build()
                   })
    yield new S3VideoStorageImpl(client, presigner, config)
