package org.typelevel.video.streaming.frontend.pages

import calico.html.io.{*, given}
import cats.effect.IO
import fs2.concurrent.Signal
import org.typelevel.video.streaming.frontend.AppActions
import org.typelevel.video.streaming.frontend.api.{Profile, ProfileState}
import org.typelevel.video.streaming.frontend.auth.AuthStatus
import org.typelevel.video.streaming.frontend.routing.Route

object SettingsPage {

  def view(
      auth: Signal[IO, AuthStatus],
      profile: Signal[IO, ProfileState],
      actions: AppActions,
  ) =
    div(
      auth.map {
        case AuthStatus.Checking        =>
          LandingPage.view("Checking your session.")
        case AuthStatus.SignedOut       =>
          LandingPage.view()
        case AuthStatus.Failed(message) =>
          LandingPage.view(message)
        case AuthStatus.SignedIn(_)     =>
          settings(profile, actions)
      },
    )

  private def settings(
      profile: Signal[IO, ProfileState],
      actions: AppActions,
  ) =
    sectionTag(
      cls := "settings-page",
      div(
        cls := "page-title",
        p(cls := "eyebrow", "ACCOUNT"),
        h1("Settings"),
        p(cls := "lede", "View the profile data used across Typelevel Video Streaming."),
      ),
      profilePanel(profile, actions),
    )

  private def profilePanel(
      profile: Signal[IO, ProfileState],
      actions: AppActions,
  ) =
    articleTag(
      cls := "panel profile-editor",
      profile.map {
        case ProfileState.Idle            =>
          div(
            cls := "panel-body",
            p("Load your profile from the user service."),
            button(cls := "primary", onClick(actions.refreshProfile), "Load profile"),
          )
        case ProfileState.Loading         =>
          div(cls := "panel-body", p("Loading profile from the backend."))
        case ProfileState.Failed(message) =>
          div(
            cls := "panel-body",
            h2("Profile unavailable"),
            p(message),
            div(
              cls := "button-row",
              button(cls := "primary", onClick(actions.refreshProfile), "Retry"),
              button(cls := "secondary", onClick(actions.login(Route.Settings)), "Refresh sign-in"),
            ),
          )
        case ProfileState.Loaded(profile) =>
          profileDetails(profile, actions)
      },
    )

  private def profileDetails(profile: Profile, actions: AppActions) =
    div(
      cls := "profile-editor-body",
      div(
        cls := "panel-heading",
        span(cls := "panel-kicker", "Profile"),
        h2(profile.username),
      ),
      dl(
        cls := "profile-facts",
        div(dt("Username"), dd(profile.username)),
        div(dt("Email"), dd(profile.email.getOrElse("Not provided"))),
        div(dt("Profile id"), dd(profile.id)),
        div(dt("Created"), dd(profile.createdAt)),
      ),
      div(
        cls := "security-actions",
        button(
          cls := "secondary",
          onClick(actions.configureTotp),
          "Set up two-factor authentication",
        ),
      ),
    )

}
