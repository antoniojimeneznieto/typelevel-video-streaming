package typelevel.courses.routing

import calico.router.Router
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.dom.HtmlAnchorElement
import org.http4s.Uri
import org.scalajs.dom

final class Navigator(private val router: Router[IO]):
  def location = router.location

  def href(route: AppRoute): String = route.uri.renderString
  def href(uri: Uri): String        = uri.renderString

  def go(route: AppRoute): IO[Unit] = router.navigate(route.uri)
  def go(uri: Uri): IO[Unit]        = router.navigate(uri)
  def go(path: String): IO[Unit]    = router.navigate(Uri.unsafeFromString(path))

  def replace(route: AppRoute): IO[Unit] = router.teleport(route.uri)
  def replace(uri: Uri): IO[Unit]        = router.teleport(uri)
  def replace(path: String): IO[Unit]    = router.teleport(Uri.unsafeFromString(path))

  def intercept(anchor: HtmlAnchorElement[IO], route: AppRoute): Resource[IO, Unit] =
    intercept(anchor, IO.pure(route.uri))

  def intercept(anchor: HtmlAnchorElement[IO], uri: Uri): Resource[IO, Unit] =
    intercept(anchor, IO.pure(uri))

  def intercept(anchor: HtmlAnchorElement[IO], path: String): Resource[IO, Unit] =
    intercept(anchor, IO.pure(Uri.unsafeFromString(path)))

  def intercept(
      anchor: HtmlAnchorElement[IO],
      destination: IO[Uri],
  ): Resource[IO, Unit] =
    fs2.dom
      .events[IO, dom.MouseEvent](anchor.asInstanceOf[dom.EventTarget], "click")
      .evalMap(clickNativeDestination(destination))
      .compile
      .drain
      .background
      .void

  private def clickNativeDestination(
      destination: IO[Uri],
  )(event: dom.MouseEvent): IO[Unit] =
    if Navigator.shouldIntercept(event) then
      IO(event.preventDefault()) *> destination.flatMap(router.navigate)
    else IO.unit

object Navigator:
  private[routing] def shouldIntercept(
      button: Int,
      metaKey: Boolean,
      ctrlKey: Boolean,
      shiftKey: Boolean,
      altKey: Boolean,
      defaultPrevented: Boolean,
  ): Boolean =
    button == 0 &&
      !metaKey &&
      !ctrlKey &&
      !shiftKey &&
      !altKey &&
      !defaultPrevented

  private def shouldIntercept(event: dom.MouseEvent): Boolean =
    shouldIntercept(
      button           = event.button,
      metaKey          = event.metaKey,
      ctrlKey          = event.ctrlKey,
      shiftKey         = event.shiftKey,
      altKey           = event.altKey,
      defaultPrevented = event.defaultPrevented,
    )
