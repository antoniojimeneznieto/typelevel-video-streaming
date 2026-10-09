package typelevel.courses.pages

import calico.frp.given
import calico.html.io.{*, given}
import cats.effect.{IO, Resource}
import cats.kernel.Eq
import cats.syntax.all.*
import fs2.concurrent.Signal
import fs2.dom.HtmlElement
import org.http4s.Uri
import typelevel.courses.AppContext
import typelevel.courses.components.{CatalogSection, CourseCard, SiteHeader}
import typelevel.courses.routing.AppRoute
import typelevel.courses.state.{AppState, RemoteStateStatus}
import typelevel.courses.ui.{CourseView, Icon, Icons}

object MyLearningPage:
  private enum ContentMode:
    case Loading, Error
    case Empty(tab: String)
    case Courses(showProgress: Boolean)

  private case class LearningView(
      tab: String,
      inProgress: Vector[CourseView],
      saved: Vector[CourseView],
      completed: Vector[CourseView],
      status: RemoteStateStatus,
      error: Option[String],
  ):
    def courses(tab: String): Vector[CourseView] = tab match
      case "saved" => saved
      case "completed" => completed
      case _ => inProgress

    def visible: Vector[CourseView] = courses(tab)

    def mode: ContentMode = status match
      case RemoteStateStatus.Idle | RemoteStateStatus.Loading => ContentMode.Loading
      case RemoteStateStatus.Error => ContentMode.Error
      case RemoteStateStatus.Ready =>
        if visible.isEmpty then ContentMode.Empty(tab)
        else ContentMode.Courses(showProgress = tab == "progress")

  private val tabs = List(
    ("progress", "In progress", Icon.Play),
    ("saved", "Saved", Icon.Bookmark),
    ("completed", "Completed", Icon.Trophy),
  )

  private def tabUri(value: String): Uri =
    if value == "progress" then AppRoute.MyLearning.uri
    else AppRoute.MyLearning.uri.withQueryParam("tab", value)

  def apply(ctx: AppContext): Resource[IO, HtmlElement[IO]] =
    val model = (ctx.navigator.location, ctx.store.signal, ctx.catalog.courses).mapN {
      (uri, state, courses) =>
        view(uri.query.params.getOrElse("tab", "progress"), state, courses)
    }

    div(
      cls := "app-page learning-page",
      SiteHeader.AppHeader(ctx),
      content(ctx, model),
    ).widen

  private def view(
      tab: String,
      state: AppState,
      courses: Vector[CourseView],
  ): LearningView =
    val coursesById   = courses.map(course => course.course.id.value.toString -> course).toMap
    val recentCourses = state.recentCourseIds.flatMap(coursesById.get)
    val inProgress    = recentCourses.filter { course =>
      val amount = state.progress.getOrElse(course.course.id.value.toString, 0)
      amount > 0 && amount < 100
    }
    val savedCourses =
      state.favorites.flatMap(favorite => coursesById.get(favorite.courseId.value.toString))
    val completed = recentCourses.filter(course =>
      state.progress.getOrElse(course.course.id.value.toString, 0) >= 100,
    )
    val activeStatus = if tab == "saved" then state.favoritesStatus else state.progressStatus
    val activeError  = if tab == "saved" then state.favoritesError else state.progressError

    LearningView(tab, inProgress, savedCourses, completed, activeStatus, activeError)

  private def content(
      ctx: AppContext,
      model: Signal[IO, LearningView],
  ): Resource[IO, HtmlElement[IO]] =
    val visible = model.map(_.visible).changes(using Eq.fromUniversalEquals)

    def count(tab: String): Signal[IO, String] =
      (ctx.catalog.signal, model).mapN { (catalog, learning) =>
        if catalog.status == RemoteStateStatus.Ready then learning.courses(tab).size.toString
        else "—"
      }.changes

    mainTag(
      cls := "app-shell learning-main",
      headerTag(
        cls := "learning-heading",
        p(cls := "eyebrow", "Your library"),
        h1("My learning"),
        p("Everything you started, saved, and finished."),
      ),
      div(
        cls := "learning-summary",
        tabs.map { case (value, label, icon) =>
          div(
            span(Icons(icon)),
            strong(count(value)),
            small(label),
          )
        },
      ),
      div(
        cls := "learning-tabs",
        role := List("tablist"),
        aria.label := "My learning categories",
        tabs.map { case (value, label, _) =>
          val selected = model.map(_.tab == value).changes
          button(
            role := List("tab"),
            aria.selected <-- selected,
            cls <-- selected.map(active => Option.when(active)("is-active").toList),
            onClick(ctx.navigator.go(tabUri(value))),
            label,
            span(count(value)),
          )
        },
      ),
      CatalogSection(ctx, "your learning") {
        div(
          styleAttr := "display: contents",
          model.map(_.mode).changes(using Eq.fromUniversalEquals).map {
            case ContentMode.Loading =>
              div(
                cls := "empty-state learning-empty",
                aria.live := "polite",
                aria.busy := true,
                span(i(cls := "session-check__spinner", aria.hidden := true)),
                h2("Syncing your learning…"),
                p("Loading your latest progress and saved videos from the playback service."),
              ).widen
            case ContentMode.Error =>
              div(
                cls := "empty-state learning-empty",
                role := List("alert"),
                span(Icons(Icon.RefreshCw)),
                h2("Your learning could not be synced."),
                p(
                  model.map(_.error.getOrElse("The playback service could not be reached.")).changes,
                ),
                button(
                  typ := "button",
                  cls := "button button--primary",
                  onClick(ctx.store.refreshPlaybackState),
                  "Try again",
                ),
              ).widen
            case ContentMode.Courses(showProgress) =>
              CourseCard.grid(
                ctx,
                visible,
                "course-grid course-grid--three learning-grid",
                showProgress = showProgress,
              )
            case ContentMode.Empty(tab) => emptyState(ctx, tab)
          },
        ).widen
      },
    ).widen

  private def emptyState(ctx: AppContext, tab: String): Resource[IO, HtmlElement[IO]] =
    val (icon, heading, copy) = tab match
      case "saved" =>
        (
          Icon.Bookmark,
          "Save something for later.",
          "Use the bookmark on any course to keep it close.",
        )
      case "completed" =>
        (
          Icon.Trophy,
          "Your first finish is ahead.",
          "Choose a focused course and your progress will appear here.",
        )
      case _ =>
        (
          Icon.LibraryBig,
          "Ready when you are.",
          "Choose a focused course and your progress will appear here.",
        )

    div(
      cls := "empty-state learning-empty",
      span(Icons(icon)),
      h2(heading),
      p(copy),
      a.withSelf { self =>
        (
          cls := "button button--primary",
          href := ctx.navigator.href(AppRoute.Browse),
          ctx.navigator.intercept(self, AppRoute.Browse),
          "Browse the library",
        )
      },
    ).widen
