package org.typelevel.video.streaming.backend.common.auth

enum Role {
  case Admin, Creator, User
}

object Role {
  def fromString(value: String): Option[Role] =
    value match {
      case "admin"   => Some(Admin)
      case "creator" => Some(Creator)
      case "user"    => Some(User)
      case _         => None
    }
}

final case class Caller(
    subject: String,
    email: Option[String],
    preferredUsername: String,
    emailVerified: Boolean,
    roles: Set[Role],
) {
  def hasRole(role: Role): Boolean = roles.contains(role)
}
