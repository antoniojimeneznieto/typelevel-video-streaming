package typelevel.courses.components

import calico.html.io.{*, given}
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.dom.{HtmlAnchorElement, HtmlElement}
import typelevel.courses.AppContext
import typelevel.courses.routing.AppRoute
import typelevel.courses.ui.{Icon, Icons}

object Footer:
  private def internalLink(
      ctx: AppContext,
      path: String,
      copy: String,
  ): Resource[IO, HtmlAnchorElement[IO]] =
    for
      anchor <- a(href := path, copy)
      _      <- ctx.navigator.intercept(anchor, path)
    yield anchor

  private def internalLink(
      ctx: AppContext,
      route: AppRoute,
      copy: String,
  ): Resource[IO, HtmlAnchorElement[IO]] =
    internalLink(ctx, ctx.navigator.href(route), copy)

  def apply(ctx: AppContext): Resource[IO, HtmlElement[IO]] =
    footerTag(
      cls := "footer",
      div(
        cls := "shell footer__grid",
        div(
          cls := "footer__intro",
          Brand(ctx),
          p("Practical video learning for people building thoughtful Scala systems."),
        ),
        div(
          h2("Learn"),
          internalLink(ctx, AppRoute.Browse, "Course library"),
          internalLink(ctx, "/#paths", "Learning paths"),
        ),
        div(
          h2("Topics"),
          internalLink(ctx, "/search?q=Cats%20Effect", "Cats Effect"),
          internalLink(ctx, "/search?topic=Streaming", "Streaming"),
          internalLink(ctx, "/search?topic=Error%20Handling", "Error handling"),
        ),
        div(
          h2("Community"),
          a(
            href := "https://typelevel.org/",
            target := "_blank",
            rel := List("noreferrer"),
            "Typelevel.org",
          ),
          a(
            href := "https://typelevel.org/code-of-conduct.html",
            target := "_blank",
            rel := List("noreferrer"),
            "Code of Conduct",
          ),
        ),
      ),
      div(
        cls := "shell footer__bottom",
        p("© 2026 Typelevel Learning Center. Built for curious functional programmers."),
        div(
          cls := "footer__socials",
          aria.label := "Social links",
          a(
            href := "https://github.com/typelevel",
            aria.label := "GitHub",
            target := "_blank",
            rel := List("noreferrer"),
            Icons(Icon.Github),
          ),
          a(
            href := "https://discord.gg/XF3CXcMzqD",
            aria.label := "Discord",
            target := "_blank",
            rel := List("noreferrer"),
            Icons(Icon.Discord),
          ),
          a(
            href := "https://typelevel.org/blog/",
            aria.label := "Typelevel blog",
            target := "_blank",
            rel := List("noreferrer"),
            Icons(Icon.Rss),
          ),
        ),
      ),
    ).widen
