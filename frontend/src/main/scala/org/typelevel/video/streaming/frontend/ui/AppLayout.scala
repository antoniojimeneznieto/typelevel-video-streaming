package org.typelevel.video.streaming.frontend.ui

import calico.html.io.{*, given}
import cats.effect.{IO, Resource}
import fs2.concurrent.Signal
import org.scalajs.dom
import org.typelevel.video.streaming.frontend.AppActions
import org.typelevel.video.streaming.frontend.auth.{AuthSession, AuthStatus, UserIdentity}
import org.typelevel.video.streaming.frontend.routing.Route

object AppLayout:

  def view(
      auth: Signal[IO, AuthStatus],
      notice: Signal[IO, Option[String]],
      actions: AppActions,
      page: Signal[IO, Resource[IO, fs2.dom.HtmlElement[IO]]]
  ) =
    div(
      cls := "app-shell",
      headerTag(
        cls := "app-header",
        a(
          cls := "app-brand",
          href := "#/",
          onClick(actions.goHome),
          img(src := "logo.svg", alt := "Typelevel"),
          span("Video Streaming")
        ),
        div(cls := "session-actions", auth.map(sessionActions(_, actions)))
      ),
      notice.map {
        case Some(message) =>
          div(
            cls := "notice",
            span(message),
            button(
              cls := "icon-button",
              aria.label := "Dismiss message",
              onClick(actions.clearNotice),
              "×"
            )
          )
        case None =>
          div(cls := "notice empty")
      },
      mainTag(cls := "app-main", page)
    )

  private def sessionActions(status: AuthStatus, actions: AppActions) =
    status match
      case AuthStatus.Checking =>
        div(cls := "session-state", "Checking session")
      case AuthStatus.SignedOut =>
        button(cls := "secondary", onClick(actions.login(Route.Home)), "Sign in")
      case AuthStatus.SignedIn(session) =>
        profileMenu(session, actions)
      case AuthStatus.Failed(message) =>
        div(
          cls := "session-cluster",
          span(cls := "session-error", message),
          button(cls := "secondary", onClick(actions.login(Route.Home)), "Try again")
        )

  private def profileMenu(session: AuthSession, actions: AppActions) =
    val identity = UserIdentity.fromSession(session)

    detailsTag(
      cls := "profile-menu",
      summaryTag(
        cls := "profile-trigger",
        aria.label := s"Open account menu for ${identity.name}",
        identity.pictureUrl match
          case Some(url) =>
            img(cls := "profile-avatar-image", src := url, alt := identity.name)
          case None =>
            span(cls := "profile-avatar", aria.hidden := true, identity.initials)
      ),
      div(
        cls := "profile-menu-panel",
        button(
          cls := "profile-menu-item",
          onClick(closeProfileMenus *> actions.goSettings),
          "Account settings"
        ),
        button(cls := "profile-menu-item", onClick(closeProfileMenus *> actions.logout), "Sign out")
      )
    )

  private def closeProfileMenus: IO[Unit] =
    IO {
      val menus = dom.document.querySelectorAll(".profile-menu[open]")
      (0 until menus.length).foreach { index =>
        menus.item(index).removeAttribute("open")
      }
    }
