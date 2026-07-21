package org.typelevel.video.streaming.frontend.pages

import calico.html.io.{*, given}

object LandingPage:

  def view(message: String = "") =
    sectionTag(
      cls := "landing-page",
      div(
        cls := "landing-panel",
        p(cls := "eyebrow", "TYPELEVEL VIDEO STREAMING"),
        h1("Typelevel Video Streaming"),
        if message.nonEmpty then p(cls := "lede", message) else p(cls := "lede empty", "")
      )
    )
