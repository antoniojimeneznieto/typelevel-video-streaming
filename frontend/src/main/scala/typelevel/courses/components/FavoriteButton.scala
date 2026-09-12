package typelevel.courses.components

import calico.frp.given
import calico.html.io.{*, given}
import cats.effect.{IO, Resource}
import cats.kernel.Eq
import cats.syntax.all.*
import fs2.concurrent.Signal
import fs2.dom.HtmlElement
import typelevel.courses.AppContext
import typelevel.courses.domain.Course
import typelevel.courses.state.RemoteStateStatus
import typelevel.courses.ui.{Icon, Icons}

object FavoriteButton:
  private enum Variant:
    case Card, Featured, Detail

    def classes: List[String] = this match
      case Card => List("icon-button", "icon-button--small")
      case Featured => List("button", "button--glass", "button--square")
      case Detail => List("button", "button--outline", "button--large")

  private final case class State(
      saved: Boolean,
      saving: Boolean,
      canSave: Boolean,
      anonymous: Boolean
  )

  def card(ctx: AppContext, course: Signal[IO, Course]): Resource[IO, HtmlElement[IO]] =
    render(ctx, course, Variant.Card)

  def featured(ctx: AppContext, course: Course): Resource[IO, HtmlElement[IO]] =
    render(ctx, Signal.constant(course), Variant.Featured)

  def detail(ctx: AppContext, course: Course): Resource[IO, HtmlElement[IO]] =
    render(ctx, Signal.constant(course), Variant.Detail)

  private def render(
      ctx: AppContext,
      course: Signal[IO, Course],
      variant: Variant
  ): Resource[IO, HtmlElement[IO]] =
    val state = (
      course,
      ctx.store.user,
      ctx.store.saved,
      ctx.store.favoritesStatus,
      ctx.store.pendingFavoriteIds
    ).mapN { (course, user, saved, status, pending) =>
      val saving = pending.contains(course.id)
      State(
        saved   = saved.contains(course.id),
        saving  = saving,
        canSave = status == RemoteStateStatus.Ready && !saving &&
          (variant != Variant.Card || user.nonEmpty),
        anonymous = user.isEmpty
      )
    }.changes(using Eq.fromUniversalEquals)
    val saved     = state.map(_.saved).changes
    val saving    = state.map(_.saving).changes
    val iconLabel = variant match
      case Variant.Card =>
        Some((course, state).mapN { (course, state) =>
          if state.saving then s"Saving ${course.title}"
          else if state.saved then s"Remove ${course.title} from saved"
          else s"Save ${course.title}"
        }.changes)
      case Variant.Featured =>
        Some(state.map { state =>
          if state.saving then "Saving featured content"
          else if state.saved then "Remove featured content from saved"
          else "Save featured content"
        }.changes)
      case Variant.Detail => None

    button(
      cls <-- saved.map(value => variant.classes ++ Option.when(value)("is-active")),
      typ := "button",
      // Only card buttons disappear for anonymous users; hero buttons keep their layout.
      Option.when(variant == Variant.Card)(hidden <-- state.map(_.anonymous).changes),
      disabled <-- state.map(!_.canSave).changes,
      aria.busy <-- saving,
      iconLabel.map(label => aria.label <-- label),
      onClick(course.get.flatMap(value => ctx.store.toggleSaved(value.id))),
      saved.map(value => Icons(if value then Icon.Check else Icon.Bookmark)),
      Option.when(variant == Variant.Detail)(state.map { state =>
        if state.saving then " Saving…"
        else if state.saved then " Saved to my learning"
        else " Save for later"
      }.changes)
    ).widen
