package org.typelevel.video.streaming.frontend.pages

import calico.html.io.{*, given}
import cats.effect.IO
import fs2.concurrent.Signal
import org.typelevel.video.streaming.frontend.auth.AuthStatus

object HomePage {

  def view(auth: Signal[IO, AuthStatus]) =
    div(
      auth.map {
        case AuthStatus.SignedIn(_)     =>
          signedIn
        case AuthStatus.Checking        =>
          LandingPage.view("Checking your session.")
        case AuthStatus.Failed(message) =>
          LandingPage.view(message)
        case AuthStatus.SignedOut       =>
          LandingPage.view()
      },
    )

  private def signedIn =
    sectionTag(
      cls := "home-page",
      div(
        cls := "home-hero",
        p(cls := "eyebrow", "HOME"),
        h1("Welcome to Typelevel Video Streaming."),
        p(cls := "lede", "Videos will live here."),
      ),
    )

}
