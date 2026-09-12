package org.typelevel.video.streaming.backend.runtime.auth

import java.nio.file.{Files, Path}
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.security.KeyFactory
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.util.Base64

import cats.effect.IO

object RsaKeyLoader:

  def privateKey(path: Path): IO[RSAPrivateKey] =
    readDer(path).flatMap { bytes =>
      IO.blocking {
        KeyFactory
          .getInstance("RSA")
          .generatePrivate(new PKCS8EncodedKeySpec(bytes))
          .asInstanceOf[RSAPrivateKey]
      }
    }

  def publicKey(path: Path): IO[RSAPublicKey] =
    readDer(path).flatMap { bytes =>
      IO.blocking {
        KeyFactory
          .getInstance("RSA")
          .generatePublic(new X509EncodedKeySpec(bytes))
          .asInstanceOf[RSAPublicKey]
      }
    }

  private def readDer(path: Path): IO[Array[Byte]] =
    IO.blocking {
      val base64 = Files
        .readAllLines(path)
        .toArray(Array.empty[String])
        .filterNot(_.startsWith("-----"))
        .mkString

      Base64.getDecoder.decode(base64)
    }
