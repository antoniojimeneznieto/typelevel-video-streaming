package org.typelevel.video.streaming.frontend.routing

import cats.effect.IO
import org.scalajs.dom

enum Route:
  case Home, Settings

  def hash: String =
    this match
      case Home     => "#/"
      case Settings => "#/settings"

object Route:

  def current: Route =
    fromHash(dom.window.location.hash)

  def fromHash(hash: String): Route =
    hash.stripPrefix("#").stripSuffix("/") match
      case "/settings" | "settings" | "/profile" | "profile" => Route.Settings
      case _                                                 => Route.Home

  def navigate(route: Route): IO[Unit] =
    IO {
      if dom.window.location.hash == route.hash then ()
      else dom.window.location.hash = route.hash
    }
