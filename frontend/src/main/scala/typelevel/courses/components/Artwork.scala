package typelevel.courses.components

import calico.html.io.{*, given}
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.dom.HtmlElement
import typelevel.courses.domain.ArtworkVariant

object Artwork:
  def apply(
      variant: ArtworkVariant,
      label: String,
      className: String         = "",
      thumbnail: Option[String] = None
  ): Resource[IO, HtmlElement[IO]] =
    thumbnail match
      case Some(path) =>
        div(
          cls := List("artwork artwork--thumbnail", className).filter(_.nonEmpty).mkString(" "),
          aria.hidden := true,
          img(cls := "artwork__thumbnail", src := path, alt := "")
        ).widen
      case None =>
        div(
          cls := List(s"artwork artwork--${variant.cssName}", className)
            .filter(_.nonEmpty)
            .mkString(" "),
          aria.hidden := true,
          div(cls := "artwork__mesh"),
          span(cls := "artwork__orb artwork__orb--one"),
          span(cls := "artwork__orb artwork__orb--two"),
          span(cls := "artwork__line artwork__line--one"),
          span(cls := "artwork__line artwork__line--two"),
          span(cls := "artwork__tile artwork__tile--one"),
          span(cls := "artwork__tile artwork__tile--two"),
          span(cls := "artwork__label", label)
        ).widen
