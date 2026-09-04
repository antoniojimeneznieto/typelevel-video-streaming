package org.typelevel.video.streaming.frontend.auth

import cats.effect.IO
import io.circe.{Decoder, Encoder}

import java.util.Base64
import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.scalajs.js.typedarray.{ArrayBuffer, ArrayBufferView, Uint8Array}

final private[auth] case class PkceState(
    state: String,
    verifier: String,
    returnTo: String,
    createdAtMillis: Double,
) derives Decoder,
      Encoder {

  def isFresh(nowMillis: Double, maxAge: FiniteDuration): Boolean =
    createdAtMillis > nowMillis - maxAge.toMillis

}

private[auth] object Pkce {

  final case class Challenge(storedState: PkceState, codeChallenge: String) {
    def state: String =
      storedState.state
  }

  def create(returnTo: String, nowMillis: Double): IO[Challenge] =
    for {
      verifier      <- randomVerifier
      codeChallenge <- challengeFor(verifier)
      state         <- randomVerifier
    } yield Challenge(
      storedState = PkceState(
        state = state,
        verifier = verifier,
        returnTo = returnTo,
        createdAtMillis = nowMillis,
      ),
      codeChallenge = codeChallenge,
    )

  private def randomVerifier: IO[String] =
    IO {
      val bytes = new Uint8Array(32)
      val _ = WebCrypto.getRandomValues(bytes)
      base64Url(bytes)
    }

  private def challengeFor(verifier: String): IO[String] = {
    val digest: IO[ArrayBuffer] =
      IO.fromFuture(IO {
        val data = new TextEncoder().encode(verifier)
        WebCrypto.subtle.digest("SHA-256", data).toFuture
      })

    digest.map(buffer => base64Url(new Uint8Array(buffer)))
  }

  private def base64Url(bytes: Uint8Array): String =
    Base64.getUrlEncoder
      .withoutPadding()
      .encodeToString(Array.tabulate(bytes.length)(index => bytes(index).toByte))

  @js.native
  @JSGlobal("crypto")
  private object WebCrypto extends js.Object {
    def getRandomValues(array: Uint8Array): Uint8Array = js.native
    val subtle: SubtleCrypto                           = js.native
  }

  @js.native
  private trait SubtleCrypto extends js.Object {
    def digest(algorithm: String, data: ArrayBufferView): js.Promise[ArrayBuffer] = js.native
  }

  @js.native
  @JSGlobal("TextEncoder")
  private class TextEncoder extends js.Object {
    def encode(input: String): Uint8Array = js.native
  }

}
