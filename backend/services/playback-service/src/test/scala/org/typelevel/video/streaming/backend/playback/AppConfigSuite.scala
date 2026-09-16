package org.typelevel.video.streaming.backend.playback

import org.typelevel.video.streaming.backend.playback.config.AppConfig
import weaver.SimpleIOSuite

object AppConfigSuite extends SimpleIOSuite:

  pureTest("storage endpoints accept HTTP(S) origins only") {
    val valid =
      List("http://localhost:9000", "http://minio:9000/", "https://s3.eu-west-1.amazonaws.com")
    val invalid = List(
      "",
      "minio:9000",
      "file:///data",
      "https://",
      "https://user:secret@example.com",
      "http://localhost:0",
      "http://localhost:65536",
      "https://example.com/bucket",
      "https://example.com?key=value",
      "https://example.com#fragment",
    )

    expect.all(
      valid.forall(AppConfig.endpointDecoder.decode(None, _).isRight),
      invalid.forall(AppConfig.endpointDecoder.decode(None, _).isLeft),
    )
  }

  pureTest("signed URL lifetimes are bounded to one hour") {
    expect.all(
      List("1", "900", "3600").forall(AppConfig.expiresInDecoder.decode(None, _).isRight),
      List("0", "-1", "3601", "604800", "nope")
        .forall(AppConfig.expiresInDecoder.decode(None, _).isLeft),
    )
  }
