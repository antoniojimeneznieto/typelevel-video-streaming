package org.typelevel.video.streaming.backend.runtime.auth

import java.nio.file.{Files, Path}
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.security.KeyFactory
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.util.Base64

import cats.effect.IO

/** Loads the RSA key pair used to sign and verify access tokens.
  *
  * {{{
  * -----BEGIN PRIVATE KEY-----
  * MIIEvQIBADANBgkqhkiG9w0BAQEFAASC...
  * -----END PRIVATE KEY-----
  *
  * -----BEGIN PUBLIC KEY-----
  * MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A...
  * -----END PUBLIC KEY-----
  * }}}
  *
  * Load the unencrypted PKCS#8 private key and X.509 public key with:
  *
  * {{{
  * for
  *   signingKey      <- RsaKeyLoader.privateKey(Path.of("private-key.pem"))
  *   verificationKey <- RsaKeyLoader.publicKey(Path.of("public-key.pem"))
  * yield (signingKey, verificationKey)
  * }}}
  */
object RsaKeyLoader:

  /** Reads a `-----BEGIN PRIVATE KEY-----` PEM file and creates an RSA private key. */
  def privateKey(path: Path): IO[RSAPrivateKey] =
    readDer(path).flatMap { bytes =>
      IO.blocking {
        KeyFactory
          .getInstance("RSA")
          .generatePrivate(new PKCS8EncodedKeySpec(bytes))
          .asInstanceOf[RSAPrivateKey]
      }
    }

  /** Reads a `-----BEGIN PUBLIC KEY-----` PEM file and creates an RSA public key. */
  def publicKey(path: Path): IO[RSAPublicKey] =
    readDer(path).flatMap { bytes =>
      IO.blocking {
        KeyFactory
          .getInstance("RSA")
          .generatePublic(new X509EncodedKeySpec(bytes))
          .asInstanceOf[RSAPublicKey]
      }
    }

  /** Removes the PEM boundary lines and Base64-decodes the body into DER bytes. */
  private def readDer(path: Path): IO[Array[Byte]] =
    IO.blocking {
      val base64 = Files
        .readAllLines(path)
        .toArray(Array.empty[String])
        .filterNot(_.startsWith("-----"))
        .mkString

      Base64.getDecoder.decode(base64)
    }
