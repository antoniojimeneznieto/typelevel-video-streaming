package org.typelevel.video.streaming.backend.playback

import java.net.URI
import java.util.concurrent.atomic.AtomicReference

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import org.http4s.Uri
import org.typelevel.video.streaming.backend.playback.api.{
  PlaybackUnavailableError,
  VideoNotFoundError
}
import org.typelevel.video.streaming.backend.playback.config.S3Config
import org.typelevel.video.streaming.backend.playback.domain.{ExpiresInSeconds, ObjectKey}
import org.typelevel.video.streaming.backend.playback.storage.S3VideoStorageImpl
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  AwsCredentialsProvider,
  StaticCredentialsProvider
}
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{HeadObjectRequest, HeadObjectResponse, S3Exception}
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}
import weaver.SimpleIOSuite

object S3VideoStorageSuite extends SimpleIOSuite:

  private val objectKey = valid(ObjectKey("published/talks/threads-at-scale.mp4"))
  private val config    = S3Config(
    endpoint               = Some(URI.create("http://minio:9000")),
    publicEndpoint         = Some(URI.create("http://localhost:9000")),
    region                 = "us-east-1",
    bucket                 = "course-videos",
    pathStyleAccessEnabled = true,
    urlExpiresIn           = valid(ExpiresInSeconds(300))
  )
  private val credentials = StaticCredentialsProvider.create(
    AwsBasicCredentials.create("test-access-key", "test-secret-key")
  )

  test("HEAD and the browser-compatible signed URL use the exact projection object key") {
    val observed = new AtomicReference[HeadObjectRequest]()
    val client   = headClient { request =>
      observed.set(request)
      HeadObjectResponse.builder().contentLength(1024L).build()
    }

    presigner(config).use { signer =>
      new S3VideoStorageImpl(client, signer, config).getPlaybackUrl(objectKey).map { response =>
        val url   = Uri.unsafeFromString(response.url.value)
        val query = url.query.params
        expect.all(
          observed.get().bucket() == config.bucket,
          observed.get().key() == objectKey.value,
          url.host.map(_.value).contains("localhost"),
          url.port.contains(9000),
          url.path.renderString == s"/${config.bucket}/${objectKey.value}",
          query.get("X-Amz-Algorithm").contains("AWS4-HMAC-SHA256"),
          query.get("X-Amz-Expires").contains("300"),
          query.get("X-Amz-SignedHeaders").contains("host"),
          query.get("X-Amz-Signature").exists(_.matches("[a-f0-9]{64}")),
          query.get("response-content-type").contains("video/mp4"),
          query.get("response-content-disposition").contains("inline"),
          response.expiresIn == config.urlExpiresIn
        )
      }
    }
  }

  test("without endpoint overrides, the signer uses the native regional S3 hostname") {
    val awsConfig = config.copy(
      endpoint               = None,
      publicEndpoint         = None,
      region                 = "eu-central-1",
      pathStyleAccessEnabled = false
    )
    val client = headClient(_ => HeadObjectResponse.builder().build())

    presigner(awsConfig).use { signer =>
      new S3VideoStorageImpl(client, signer, awsConfig).getPlaybackUrl(objectKey).map { response =>
        val url = Uri.unsafeFromString(response.url.value)
        expect.all(
          url.scheme.contains(Uri.Scheme.https),
          url.host.map(_.value).contains("course-videos.s3.eu-central-1.amazonaws.com"),
          url.path.renderString == s"/${objectKey.value}",
          url.query.params.get("X-Amz-SignedHeaders").contains("host")
        )
      }
    }
  }

  test("a missing object returns a sanitized modeled 404") {
    val client = headClient(_ => throw s3Error(404, "NoSuchKey"))

    presigner(config).use { signer =>
      new S3VideoStorageImpl(client, signer, config)
        .getPlaybackUrl(objectKey)
        .attempt
        .map { result =>
          expect(result == Left(VideoNotFoundError("Video not found")))
        }
    }
  }

  test("a missing bucket, access denial, and storage failures return sanitized modeled 503s") {
    val failures = List(
      s3Error(404, "NoSuchBucket"),
      s3Error(403, "AccessDenied"),
      s3Error(503, "SlowDown"),
      SdkClientException.create("Synthetic storage failure with private connection details")
    )

    presigner(config).use { signer =>
      failures
        .traverse { failure =>
          val client = headClient(_ => throw failure)
          new S3VideoStorageImpl(client, signer, config)
            .getPlaybackUrl(objectKey)
            .attempt
            .map(result => expect(result == Left(unavailable)))
        }
        .map(_.reduce(_ and _))
    }
  }

  test("credential resolution failures during signing do not expose SDK error details") {
    val failingCredentials: AwsCredentialsProvider = () =>
      throw SdkClientException.create("Synthetic credential provider failure with private details")
    val client = headClient(_ => HeadObjectResponse.builder().build())

    presigner(config, failingCredentials).use { signer =>
      new S3VideoStorageImpl(client, signer, config)
        .getPlaybackUrl(objectKey)
        .attempt
        .map { result =>
          expect(result == Left(unavailable))
        }
    }
  }

  private def headClient(head: HeadObjectRequest => HeadObjectResponse): S3Client =
    new S3Client:
      override def serviceName(): String                                      = "s3"
      override def close(): Unit                                              = ()
      override def headObject(request: HeadObjectRequest): HeadObjectResponse = head(request)

  private def presigner(
      settings: S3Config,
      provider: AwsCredentialsProvider = credentials
  ): Resource[IO, S3Presigner] =
    Resource.fromAutoCloseable(IO.blocking {
      val builder = S3Presigner
        .builder()
        .region(Region.of(settings.region))
        .credentialsProvider(provider)
        .serviceConfiguration(
          S3Configuration
            .builder()
            .pathStyleAccessEnabled(settings.pathStyleAccessEnabled)
            .build()
        )
      settings.publicEndpoint.orElse(settings.endpoint).foreach(builder.endpointOverride)
      builder.build()
    })

  private def s3Error(status: Int, code: String): Throwable =
    val builder = S3Exception.builder()
    builder.statusCode(status)
    builder.awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
    builder.message("Synthetic storage error with private object details")
    builder.build()

  private def unavailable: PlaybackUnavailableError =
    PlaybackUnavailableError("Video storage is temporarily unavailable")

  private def valid[A](value: Either[String, A]): A =
    value.fold(message => throw new AssertionError(message), identity)
