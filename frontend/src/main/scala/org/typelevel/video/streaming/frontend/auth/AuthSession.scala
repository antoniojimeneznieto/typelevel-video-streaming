package org.typelevel.video.streaming.frontend.auth

import io.circe.parser.decode
import io.circe.{Decoder, Encoder}

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.util.Try

final case class AuthSession(
    accessToken: String,
    refreshToken: Option[String],
    idToken: Option[String],
    expiresAtMillis: Double,
) derives Decoder,
      Encoder {
  def isFresh(nowMillis: Double): Boolean =
    expiresAtMillis > nowMillis + 15000
}

final case class UserIdentity(
    name: String,
    initials: String,
    pictureUrl: Option[String],
)

object UserIdentity {

  val fallback: UserIdentity =
    UserIdentity(
      name = "Video Streaming account",
      initials = "VS",
      pictureUrl = None,
    )

  def fromSession(session: AuthSession): UserIdentity = {
    val parsedClaims =
      session.idToken.flatMap(parseJwtPayload).orElse(parseJwtPayload(session.accessToken))

    parsedClaims
      .map { claims =>
        val name =
          firstNonEmpty(claims.name, claims.preferred_username, claims.email)
            .getOrElse(fallback.name)

        UserIdentity(
          name = name,
          initials = initialsFor(name),
          pictureUrl = nonEmpty(claims.picture),
        )
      }
      .getOrElse(fallback)
  }

  private def parseJwtPayload(token: String): Option[JwtClaims] =
    token.split("\\.").lift(1).flatMap { payload =>
      Try(decodeBase64Url(payload)).toOption.flatMap { decoded =>
        decode[JwtClaims](decoded).toOption
      }
    }

  private def decodeBase64Url(value: String): String = {
    val bytes = Base64.getUrlDecoder.decode(paddedBase64(value))
    new String(bytes, StandardCharsets.UTF_8)
  }

  private def paddedBase64(value: String): String = {
    val padding = (4 - value.length % 4) % 4
    value + ("=" * padding)
  }

  private def initialsFor(name: String): String = {
    val words =
      name
        .split("[\\s@._-]+")
        .toList
        .filter(_.nonEmpty)

    val initials = words
      .take(2)
      .flatMap(_.headOption)
      .mkString
      .toUpperCase
      .take(2)

    if initials.nonEmpty then initials else fallback.initials
  }

  final private case class JwtClaims(
      name: Option[String],
      preferred_username: Option[String],
      email: Option[String],
      picture: Option[String],
  ) derives Decoder

  private def firstNonEmpty(values: Option[String]*): Option[String] =
    values.collectFirst(Function.unlift(nonEmpty))

  private def nonEmpty(value: Option[String]): Option[String] =
    value.map(_.trim).filter(_.nonEmpty)

}

enum AuthStatus {
  case Checking
  case SignedOut
  case SignedIn(session: AuthSession)
  case Failed(message: String)

  def sessionOption: Option[AuthSession] =
    this match {
      case SignedIn(session) => Some(session)
      case _                 => None
    }

}
