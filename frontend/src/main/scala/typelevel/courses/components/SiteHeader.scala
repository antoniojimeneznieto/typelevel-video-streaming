package typelevel.courses.components

import calico.html.io.{*, given}
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import fs2.dom.{HtmlAnchorElement, HtmlDivElement, HtmlElement, HtmlInputElement}
import org.http4s.Uri
import org.scalajs.dom
import typelevel.courses.AppContext
import typelevel.courses.routing.AppRoute
import typelevel.courses.state.RemoteStateStatus
import typelevel.courses.ui.CatalogPresentation.*
import typelevel.courses.ui.{CourseView, FormEvents, Icon, Icons}

object SiteHeader:
  private def routeLink(
      ctx: AppContext,
      route: AppRoute,
      copy: String,
      className: String = "",
  ): Resource[IO, HtmlAnchorElement[IO]] =
    pathLink(ctx, ctx.navigator.href(route), copy, className)

  private def pathLink(
      ctx: AppContext,
      path: String,
      copy: String,
      className: String = "",
  ): Resource[IO, HtmlAnchorElement[IO]] =
    for
      anchor <- a(cls := className, href := path, copy)
      _      <- ctx.navigator.intercept(anchor, path)
    yield anchor

  private def activeRouteLink(
      ctx: AppContext,
      route: AppRoute,
      copy: String,
  ): Resource[IO, HtmlAnchorElement[IO]] =
    val routePath = AppRoute.normalizedPath(route.uri)
    for
      anchor <- a(
                  cls <-- ctx.navigator.location.map { current =>
                    if AppRoute.normalizedPath(current) == routePath then List("active") else Nil
                  },
                  href := ctx.navigator.href(route),
                  copy,
                )
      _ <- ctx.navigator.intercept(anchor, route)
    yield anchor

  private def mobileMenuButton(open: SignallingRef[IO, Boolean]): Resource[IO, HtmlElement[IO]] =
    button(
      cls := "mobile-menu-button",
      typ := "button",
      aria.label <-- open.map(if _ then "Close menu" else "Open menu"),
      aria.expanded <-- open,
      onClick(open.update(!_)),
      open.map(value => Icons(if value then Icon.X else Icon.Menu)),
    ).widen

  private def globalSearchShortcuts(open: SignallingRef[IO, Boolean]): Resource[IO, Unit] =
    val handle = fs2.dom
      .events[IO, dom.KeyboardEvent](dom.window, "keydown")
      .evalMap { event =>
        val isTyping = event.target match
          case _: dom.HTMLInputElement => true
          case _: dom.HTMLTextAreaElement => true
          case element: dom.HTMLElement => element.isContentEditable
          case _ => false

        val opensSearch =
          (!isTyping && event.key == "/") ||
            ((event.metaKey || event.ctrlKey) && event.key.equalsIgnoreCase("k"))

        if opensSearch then IO(event.preventDefault()) *> open.set(true)
        else if event.key == "Escape" then open.set(false)
        else IO.unit
      }
      .compile
      .drain

    (IO.cede *> handle).background.void

  private def searchUri(query: String): Uri =
    AppRoute.Search.uri.withQueryParam("q", query.trim)

  private def quickCourses(query: String, courses: Vector[CourseView]): Vector[CourseView] =
    val normalized = query.trim.toLowerCase
    if normalized.isEmpty then courses.take(4)
    else
      courses
        .filter { view =>
          val course = view.course
          (Vector(
            course.title.value,
            view.shortDescription,
            course.topic.value,
          ) ++ course.technologies.map(_.value)).mkString(" ").toLowerCase.contains(normalized)
        }
        .take(5)

  private def quickResult(
      ctx: AppContext,
      course: CourseView,
      close: IO[Unit],
  ): Resource[IO, HtmlAnchorElement[IO]] =
    val destination = AppRoute.Course(course.course.slug.value)
    for
      anchor <- a(
                  cls := "quick-result",
                  href := ctx.navigator.href(destination),
                  Artwork(course.artwork, course.artLabel, thumbnail = course.thumbnail),
                  span(
                    strong(course.course.title.value),
                    small(s"${course.course.topic.value} · ${course.duration}"),
                  ),
                  Icons(Icon.ChevronRight),
                )
      _ <- ctx.navigator.intercept(anchor, close.as(destination.uri))
    yield anchor

  final private case class DialogLifecycle(
      previousFocus: Option[dom.HTMLElement],
      previousOverflow: String,
      focusTimer: Int,
  )

  private def dialogLifecycle(inputElement: HtmlInputElement[IO]): Resource[IO, Unit] =
    Resource.make {
      IO.delay {
        val previousFocus = Option(dom.document.activeElement).collect {
          case element: dom.HTMLElement => element
        }
        val previousOverflow = dom.document.body.style.overflow
        dom.document.body.style.overflow = "hidden"
        val inputNode  = inputElement.asInstanceOf[dom.HTMLInputElement]
        val focusTimer = dom.window.setTimeout(() => inputNode.focus(), 30)
        DialogLifecycle(previousFocus, previousOverflow, focusTimer)
      }
    } { lifecycle =>
      IO.delay {
        dom.window.clearTimeout(lifecycle.focusTimer)
        dom.document.body.style.overflow = lifecycle.previousOverflow
        lifecycle.previousFocus.foreach(_.focus())
      }
    }.void

  private def keepFocusInside(
      event: fs2.dom.KeyboardEvent[IO],
      panel: HtmlDivElement[IO],
      close: IO[Unit],
  ): IO[Unit] =
    if event.key == "Escape" then close
    else if event.key != "Tab" then IO.unit
    else
      IO.delay {
        val panelElement = panel.asInstanceOf[dom.HTMLDivElement]
        val matches      = panelElement.querySelectorAll(
          "a[href], button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex='-1'])",
        )
        (0 until matches.length).toVector
          .map(index => matches.item(index).asInstanceOf[dom.HTMLElement])
      }.flatMap { focusable =>
        focusable.headOption.zip(focusable.lastOption) match
          case Some((first, last)) if event.shiftKey && dom.document.activeElement == first =>
            event.preventDefault *> IO(last.focus())
          case Some((first, last)) if !event.shiftKey && dom.document.activeElement == last =>
            event.preventDefault *> IO(first.focus())
          case _ => IO.unit
      }

  private def SearchDialog(
      ctx: AppContext,
      close: IO[Unit],
  ): Resource[IO, HtmlElement[IO]] =
    for
      query <- SignallingRef[IO].of("").toResource
      search = (query, ctx.catalog.courses).mapN { (current, courses) =>
                 current -> quickCourses(current, courses)
               }
      searchInput <- input.withSelf { self =>
                       (
                         value <-- query,
                         onInput(_ => self.value.get.flatMap(query.set)),
                         placeholder := "Search courses, topics, or technology…",
                         aria.label := "Search",
                       )
                     }
      formElement <- form.withSelf { self =>
                       (
                         FormEvents.preventNativeSubmit(self),
                         cls := "search-dialog__form",
                         onSubmit(
                           query.get.flatMap(value => close *> ctx.navigator.go(searchUri(value))),
                         ),
                         Icons(Icon.Search),
                         searchInput,
                         button(
                           typ := "button",
                           onClick(close),
                           aria.label := "Close search",
                           Icons(Icon.X, className = "search-dialog__mobile-close"),
                           kbd("Esc"),
                         ),
                       )
                     }
      titleRow <- div(
                    cls := "search-dialog__title-row",
                    children <-- search.map { (current, matches) =>
                      val hasQuery = current.trim.nonEmpty
                      List(
                        p(if hasQuery then s"${matches.size} quick results" else "Popular now"),
                      ) ++ Option.when(hasQuery)(
                        button(
                          typ := "button",
                          onClick(close *> ctx.navigator.go(searchUri(current))),
                          "See all results ",
                          Icons(Icon.ChevronRight),
                        ),
                      )
                    },
                  )
      results <- div(
                   cls := "search-dialog__results",
                   children <-- search.map { (_, matches) =>
                     if matches.nonEmpty then
                       matches.toList.map(course => quickResult(ctx, course, close))
                     else
                       List(
                         div(
                           cls := "search-dialog__empty",
                           p("No quick matches yet."),
                           span("Try “effects”, “Scala”, or “http4s”."),
                         ),
                       )
                   },
                 )
      panel <- div(
                 cls := "search-dialog__panel",
                 formElement,
                 div(
                   cls := "search-dialog__content",
                   titleRow,
                   results,
                 ),
                 div(
                   cls := "search-dialog__footer",
                   span(kbd("↵"), " select"),
                   span(kbd("/"), " open search"),
                 ),
               )
      root <- div(
                cls := "search-dialog",
                role := List("dialog"),
                aria.label := "Search the course library",
                onKeyDown(event => keepFocusInside(event, panel, close)),
                button(
                  typ := "button",
                  cls := "search-dialog__backdrop",
                  onClick(close),
                  aria.label := "Close search",
                  tabIndex := -1,
                ),
                panel,
              )
      _ <- Resource.eval(
             IO.delay(
               root.asInstanceOf[dom.HTMLDivElement].setAttribute("aria-modal", "true"),
             ),
           )
      _ <- dialogLifecycle(searchInput)
    yield root

  def PublicHeader(ctx: AppContext): Resource[IO, HtmlElement[IO]] =
    for
      menuOpen <- SignallingRef[IO].of(false).toResource
      header   <- headerTag(
                  cls := "site-header site-header--public",
                  div(
                    cls := "shell site-header__inner",
                    Brand(ctx),
                    navTag(
                      cls <-- menuOpen.map(open =>
                        List("site-nav") ++ Option.when(open)("is-open"),
                      ),
                      aria.label := "Main navigation",
                      onClick(menuOpen.set(false)),
                      pathLink(ctx, "/#courses", "Courses"),
                      pathLink(ctx, "/#paths", "Learning paths"),
                      pathLink(ctx, "/#why-typelevel", "Why Typelevel"),
                      routeLink(ctx, AppRoute.Login(), "Log in", "site-nav__mobile-auth"),
                      routeLink(
                        ctx,
                        AppRoute.Register,
                        "Start learning",
                        "site-nav__mobile-auth site-nav__mobile-auth--primary",
                      ),
                    ),
                    div(
                      cls := "site-header__actions",
                      routeLink(ctx, AppRoute.Login(), "Log in", "button button--quiet login-link"),
                      routeLink(
                        ctx,
                        AppRoute.Register,
                        "Start learning",
                        "button button--primary register-link",
                      ),
                      mobileMenuButton(menuOpen),
                    ),
                  ),
                )
    yield header

  def AppHeader(ctx: AppContext): Resource[IO, HtmlElement[IO]] =
    val user       = ctx.store.user
    val savedCount = ctx.store.saved.map(_.size)

    for
      menuOpen   <- SignallingRef[IO].of(false).toResource
      searchOpen <- SignallingRef[IO].of(false).toResource
      _          <- globalSearchShortcuts(searchOpen)
      savedLink  <- a(
                     cls := "saved-link",
                     href := "/my-learning?tab=saved",
                     aria.label <-- savedCount.map(count => s"$count saved courses"),
                     Icons(Icon.Bookmark),
                     savedCount.map(count => Option.when(count > 0)(span(count.toString))),
                   )
      _      <- ctx.navigator.intercept(savedLink, "/my-learning?tab=saved")
      header <- headerTag(
                  cls := "site-header site-header--app",
                  div(
                    cls := "app-shell site-header__inner",
                    Brand(ctx, to = AppRoute.Browse),
                    navTag(
                      cls <-- menuOpen.map(open => List("app-nav") ++ Option.when(open)("is-open")),
                      aria.label := "Course navigation",
                      onClick(menuOpen.set(false)),
                      activeRouteLink(ctx, AppRoute.Browse, "Browse"),
                      activeRouteLink(ctx, AppRoute.Paths, "Paths"),
                      activeRouteLink(ctx, AppRoute.MyLearning, "My learning"),
                    ),
                    div(
                      cls := "app-header__actions",
                      button(
                        typ := "button",
                        cls := "app-search-trigger",
                        onClick(searchOpen.set(true)),
                        aria.label := "Search the course library",
                        Icons(Icon.Search),
                        span("Search the library"),
                        kbd("⌘ K"),
                      ),
                      savedLink,
                      detailsTag(
                        cls := "user-menu",
                        summaryTag(
                          aria.label := "Open account menu",
                          user.map {
                            case Some(value) => span(value.displayName.value.take(1).toUpperCase)
                            case None => Icons(Icon.UserRound)
                          },
                        ),
                        div(
                          cls := "user-menu__panel",
                          div(
                            cls := "user-menu__identity",
                            strong(user.map(_.fold("Guest learner")(_.displayName.value))),
                            span(user.map(_.fold("Learning synced to your account")(_.email.value))),
                          ),
                          user.map {
                            case None => routeLink(ctx, AppRoute.Login(), "Log in")
                            case Some(_) =>
                              button(
                                typ := "button",
                                onClick(ctx.store.signOut *> ctx.navigator.go(AppRoute.Landing)),
                                "Log out",
                              )
                          },
                        ),
                      ),
                      mobileMenuButton(menuOpen),
                    ),
                  ),
                )
      root <- div(
                styleAttr := "display: contents",
                header,
                ctx.store.signal.map { state =>
                  state.playbackError.map { message =>
                    div(
                      cls := "playback-service-alert",
                      role := List(
                        if state.playbackStatus == RemoteStateStatus.Error then "alert"
                        else "status",
                      ),
                      div(
                        cls := "app-shell",
                        span(message),
                        button(
                          typ := "button",
                          onClick(ctx.store.refreshPlaybackState),
                          "Check again",
                        ),
                      ),
                    )
                  }
                },
                searchOpen.map(open => Option.when(open)(SearchDialog(ctx, searchOpen.set(false)))),
              )
    yield root
