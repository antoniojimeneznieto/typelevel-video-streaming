package typelevel.courses.ui

import scala.scalajs.js

import cats.effect.{IO, Resource}
import fs2.dom.HtmlElement
import org.scalajs.dom

private[courses] object FormEvents:
  def preventNativeSubmit(form: HtmlElement[IO]): Resource[IO, Unit] =
    val target = form.asInstanceOf[dom.HTMLFormElement]
    // Calico runs its submit handler asynchronously, after the browser's default action.
    val prevent: js.Function1[dom.Event, Unit] = _.preventDefault()
    Resource.make(IO.delay(target.addEventListener("submit", prevent)))(_ =>
      IO.delay(target.removeEventListener("submit", prevent)),
    )
