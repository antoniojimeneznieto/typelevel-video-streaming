package typelevel.courses.pages

import calico.html.io.{*, given}
import cats.effect.{IO, Resource}
import fs2.dom.HtmlElement
import typelevel.courses.AppContext
import typelevel.courses.components.Brand
import typelevel.courses.routing.AppRoute
import typelevel.courses.ui.{Icon, Icons}

object NotFoundPage:
  def apply(
      ctx: AppContext,
      embedded: Boolean = false
  ): Resource[IO, HtmlElement[IO]] =
    mainTag(
      cls := s"not-found${Option.when(embedded)(" not-found--embedded").getOrElse("")}",
      Option.when(!embedded)(Brand(ctx)),
      div(cls := "not-found__code", "404"),
      span(cls := "not-found__icon", Icons(Icon.Compass)),
      p(cls := "eyebrow", "That path ends here"),
      h1("Let’s get you back to the useful part."),
      p("The page may have moved, or the course is still being prepared."),
      a.withSelf { self =>
        (
          cls := "button button--primary",
          href := ctx.navigator.href(AppRoute.Browse),
          ctx.navigator.intercept(self, AppRoute.Browse),
          Icons(Icon.ArrowLeft),
          " Browse courses"
        )
      }
    )
