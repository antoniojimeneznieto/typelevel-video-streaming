package org.typelevel.video.streaming.backend.common.auth

enum AuthError {
  case MissingToken
  case InvalidToken(reason: String)
}
