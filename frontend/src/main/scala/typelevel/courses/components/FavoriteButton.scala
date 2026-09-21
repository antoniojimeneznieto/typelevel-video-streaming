package typelevel.courses.components

import calico.frp.given
import calico.html.io.{*, given}
import cats.effect.{IO, Resource}
import cats.kernel.Eq
import cats.syntax.all.*
import fs2.concurrent.Signal
import fs2.dom.HtmlElement
import typelevel.courses.AppContext
import typelevel.courses.state.RemoteStateStatus
import typelevel.courses.ui.{CourseView, Icon, Icons}

object FavoriteButton:
  private enum Variant:
    case Card, Featured, Detail

    def classes: List[String] = this match
      case Card => List("icon-button", "icon-button--small")
      case Featured => List("button", "button--glass", "button--square")
      case Detail => List("button", "button--outline", "button--large")

  final private case class State(
      saved: Boolean,
      saving: Boolean,
      canSave: Boolean,
      anonymous: Boolean,
  )

  def card(ctx: AppContext, course: Signal[IO, CourseView]): Resource[IO, HtmlElement[IO]] =
    render(ctx, course, Variant.Card)

  def featured(ctx: AppContext, course: CourseView): Resource[IO, HtmlElement[IO]] =
    render(ctx, Signal.constant(course), Variant.Featured)

  def detail(ctx: AppContext, course: CourseView): Resource[IO, HtmlElement[IO]] =
    render(ctx, Signal.constant(course), Variant.Detail)

  private def render(
      ctx: AppContext,
      course: Signal[IO, CourseView],
      variant: Variant,
  ): Resource[IO, HtmlElement[IO]] =
    val courseId = course.map(_.course.id.value.toString).changes
    val state    = (
      courseId,
      ctx.store.user,
      ctx.store.saved,
      ctx.store.favoritesStatus,
      ctx.store.pendingFavoriteIds,
    ).mapN { (id, user, saved, status, pending) =>
      val saving = pending.contains(id)
      State(
        saved   = saved.contains(id),
        saving  = saving,
        canSave = status == RemoteStateStatus.Ready && !saving &&
          (variant != Variant.Card || user.nonEmpty),
        anonymous = user.isEmpty,
      )
    }.changes(using Eq.fromUniversalEquals)
    val saved     = state.map(_.saved).changes
    val saving    = state.map(_.saving).changes
    val iconLabel = Option.unless(variant == Variant.Detail) {
      val subject: Signal[IO, String] =
        if variant == Variant.Card then course.map(_.course.title.value)
        else Signal.constant("featured content")
      (subject, state).mapN { (subject, state) =>
        if state.saving then s"Saving $subject"
        else if state.saved then s"Remove $subject from saved"
        else s"Save $subject"
      }.changes
    }

    button(
      cls <-- saved.map(value => variant.classes ++ Option.when(value)("is-active")),
      typ := "button",
      Option.when(variant == Variant.Card)(hidden <-- state.map(_.anonymous).changes),
      disabled <-- state.map(!_.canSave).changes,
      aria.busy <-- saving,
      iconLabel.map(label => aria.label <-- label),
      onClick(courseId.get.flatMap(ctx.store.toggleSaved)),
      saved.map(value => Icons(if value then Icon.Check else Icon.Bookmark)),
      Option.when(variant == Variant.Detail)(state.map { state =>
        if state.saving then " Saving…"
        else if state.saved then " Saved to my learning"
        else " Save for later"
      }.changes),
    ).widen
