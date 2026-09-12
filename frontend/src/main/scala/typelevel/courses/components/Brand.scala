package typelevel.courses.components

import calico.html.io.{*, given}
import cats.effect.{IO, Resource}
import fs2.dom.HtmlElement
import typelevel.courses.AppContext
import typelevel.courses.routing.AppRoute

object Brand:
  def apply(
      ctx: AppContext,
      compact: Boolean = false,
      light: Boolean   = false,
      to: AppRoute     = AppRoute.Landing
  ): Resource[IO, HtmlElement[IO]] =
    for
      anchor <- a(
                  cls := s"brand${if light then " brand--light" else ""}",
                  href := ctx.navigator.href(to),
                  aria.label := "Typelevel Learning Center home",
                  span(
                    cls := "brand__logo",
                    img(
                      cls := "brand__official-logo",
                      src := "/typelevel-logo.svg",
                      alt := "Typelevel"
                    ),
                    Option.when(light)(
                      img(
                        cls := "brand__official-logo brand__official-logo--light-word",
                        src := "/typelevel-logo.svg",
                        alt := "",
                        aria.hidden := true
                      )
                    )
                  ),
                  Option.unless(compact)(span(cls := "brand__suffix", "learning center"))
                )
      _ <- ctx.navigator.intercept(anchor, to)
    yield anchor
