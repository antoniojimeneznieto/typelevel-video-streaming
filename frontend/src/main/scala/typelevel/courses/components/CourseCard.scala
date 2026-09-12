package typelevel.courses.components

import calico.html.io.{*, given}
import cats.effect.{IO, Resource}
import cats.kernel.Eq
import cats.syntax.all.*
import fs2.concurrent.Signal
import fs2.dom.{HtmlElement, Node}
import typelevel.courses.AppContext
import typelevel.courses.domain.Course
import typelevel.courses.routing.AppRoute
import typelevel.courses.ui.{Icon, Icons}

object CourseCard:
  def apply(
      ctx: AppContext,
      course: Course,
      progress: Option[Int]         = None,
      compact: Boolean              = false,
      destination: Option[AppRoute] = None,
  ): Resource[IO, HtmlElement[IO]] =
    reactive(ctx, Signal.constant(course), progress.map(Signal.constant(_)), compact, destination)

  def grid(
      ctx: AppContext,
      courses: Signal[IO, Vector[Course]],
      className: String     = "course-grid",
      showProgress: Boolean = false,
      compact: Boolean      = false,
  ): Resource[IO, HtmlElement[IO]] =
    div(
      cls := className,
      children[String] { id =>
        courses.get.toResource
          .flatMap { current =>
            current.find(_.id == id) match
              case Some(initial) =>
                val course = courses
                  .map(_.find(_.id == id).getOrElse(initial))
                  .changes(using Eq.fromUniversalEquals)
                val progress = Option.when(showProgress)(ctx.store.progress.map(_.getOrElse(id, 0)))
                reactive(ctx, course, progress, compact, None)
              case None => div(())
          }
          .map(value => value: Node[IO])
      } <-- courses.map(_.map(_.id).toList),
    ).widen

  private def reactive(
      ctx: AppContext,
      course: Signal[IO, Course],
      progress: Option[Signal[IO, Int]],
      compact: Boolean,
      destination: Option[AppRoute],
  ): Resource[IO, HtmlElement[IO]] =
    val courseDestination = course.map(value => destination.getOrElse(AppRoute.Course(value.slug)))
    for
      artLink <- a(
                   cls := "course-card__art-link",
                   href <-- courseDestination.map(ctx.navigator.href),
                   aria.label <-- course.map(value => s"View ${value.title}"),
                   course
                     .map(value => (value.artwork, value.artLabel, value.thumbnail))
                     .changes(using Eq.fromUniversalEquals)
                     .map { (artwork, label, thumbnail) =>
                       Artwork(artwork, label, thumbnail = thumbnail)
                     },
                   span(cls := "course-card__format", course.map(_.format.label)),
                   span(cls := "course-card__new", hidden <-- course.map(!_.isNew), "New"),
                   span(
                     cls := "course-card__play",
                     aria.hidden := true,
                     Icons(Icon.Play),
                   ),
                 )
      titleLink <- a(
                     href <-- courseDestination.map(ctx.navigator.href),
                     course.map(_.title),
                   )
      _    <- ctx.navigator.intercept(artLink, courseDestination.get.map(_.uri))
      _    <- ctx.navigator.intercept(titleLink, courseDestination.get.map(_.uri))
      card <- articleTag(
                cls := s"course-card${if compact then " course-card--compact" else ""}",
                artLink,
                progress.map { amount =>
                  div(
                    cls := "progress-bar progress-bar--card",
                    aria.label <-- amount.map(value => s"$value% complete"),
                    role := List("progressbar"),
                    aria.valueMin := 0d,
                    aria.valueMax := 100d,
                    aria.valueNow <-- amount.map(_.toDouble),
                    span(styleAttr <-- amount.map(value => s"width: $value%")),
                  )
                },
                div(
                  cls := "course-card__body",
                  div(
                    cls := "course-card__meta-row",
                    span(cls := "eyebrow eyebrow--small", course.map(_.topic)),
                    FavoriteButton.card(ctx, course),
                  ),
                  h3(titleLink),
                  Option.unless(compact)(p(course.map(_.shortDescription))),
                  div(
                    cls := "course-card__facts",
                    span(course.map(_.level.label)),
                    span(course.map(_.duration)),
                    course
                      .map(_.rating)
                      .changes
                      .map(_.map(value => span(cls := "rating", Icons(Icon.Star), s" $value"))),
                  ),
                ),
              )
    yield card
