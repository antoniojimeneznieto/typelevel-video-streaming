package org.typelevel.video.streaming.frontend

import cats.effect.IO
import org.typelevel.video.streaming.frontend.routing.Route

final case class AppActions(
    login: Route => IO[Unit],
    logout: IO[Unit],
    goHome: IO[Unit],
    goSettings: IO[Unit],
    configureTotp: IO[Unit],
    refreshProfile: IO[Unit],
    clearNotice: IO[Unit]
)
