package typelevel.courses.components

import calico.html.io.{*, given}
import cats.effect.{IO, Resource}
import cats.effect.std.Supervisor
import cats.kernel.Eq
import cats.syntax.all.*
import fs2.dom.HtmlElement
import typelevel.courses.AppContext
import typelevel.courses.state.RemoteStateStatus
import typelevel.courses.ui.{Icon, Icons}

object CatalogSection:
  def apply(
      ctx: AppContext,
      label: String = "videos",
  )(content: => Resource[IO, HtmlElement[IO]]): Resource[IO, HtmlElement[IO]] = for
    supervisor <- Supervisor[IO](await = false)
    section    <- div(
                 styleAttr := "display: contents",
                 ctx.catalog.signal.map(_.status).changes(using Eq.fromUniversalEquals).map {
                   case RemoteStateStatus.Ready => content
                   case status =>
                     val failed = status == RemoteStateStatus.Error
                     div(
                       cls := "catalog-state",
                       role := List(if failed then "alert" else "status"),
                       aria.busy := !failed,
                       if failed then
                         List(
                           Icons(Icon.RefreshCw),
                           h3(s"Could not load $label."),
                           p("Please try again in a moment."),
                           button(
                             typ := "button",
                             cls := "button button--outline",
                             onClick(supervisor.supervise(ctx.catalog.refresh).void),
                             "Try again",
                           ),
                         )
                       else
                         List(
                           span(cls := "session-check__spinner", aria.hidden := true),
                           p(s"Loading $label…"),
                         ),
                     ).widen
                 },
               ).widen
  yield section
