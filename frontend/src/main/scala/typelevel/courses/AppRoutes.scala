package typelevel.courses

import calico.frp.given
import calico.html.io.{*, given}
import calico.router.Routes
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.concurrent.Signal
import fs2.dom.HtmlElement
import org.http4s.Uri
import typelevel.courses.pages.*
import typelevel.courses.routing.AppRoute
import typelevel.courses.state.AuthStatus

object AppRoutes:
  def build(ctx: AppContext): IO[Routes[IO]] = for
    landing  <- staticRoute(AppRoute.Landing)(LandingPage(ctx))
    login    <- authRoute(ctx, AppRoute.Login(), AuthPage.Mode.Login)
    register <- authRoute(ctx, AppRoute.Register, AuthPage.Mode.Register)
    browse   <- protectedStatic(ctx, AppRoute.Browse)(BrowsePage(ctx))
    search   <- protectedStatic(ctx, AppRoute.Search)(SearchPage(ctx))
    paths    <- protectedStatic(ctx, AppRoute.Paths)(PathsPage(ctx))
    learning <- protectedStatic(ctx, AppRoute.MyLearning)(MyLearningPage(ctx))
    course   <- Routes.one[IO] { case CoursePath(slug) =>
                slug
              } { slug =>
                routeView(slug)(value => requireAuth(ctx)(CoursePage(ctx, value)))
              }
    watch <- Routes.one[IO] { case WatchLessonPath(slug, lessonId) =>
               slug -> lessonId
             } { route =>
               requireAuth(ctx)(WatchPage(ctx, route))
             }
    watchDefault <- Routes.one[IO] { case WatchCoursePath(slug) =>
                      slug
                    } { slug =>
                      requireAuth(ctx) {
                        (IO.cede *>
                          slug.get.flatMap(value =>
                            ctx.navigator.replace(AppRoute.Watch(value, "lesson-1")),
                          )).background.void *>
                          div(cls := "route-redirect").widen
                      }
                    }
    notFound <- Routes.one[IO] { case uri if AppRoute.parse(uri) == AppRoute.NotFound => () } { _ =>
                  NotFoundPage(ctx)
                }
  yield landing |+| login |+| register |+| browse |+| search |+| paths |+| learning |+|
    course |+| watch |+| watchDefault |+| notFound

  private def staticRoute(
      expected: AppRoute,
  )(page: => Resource[IO, HtmlElement[IO]]): IO[Routes[IO]] =
    Routes.one[IO] { case uri if sameTemplate(AppRoute.parse(uri), expected) => () }(_ => page)

  private def authRoute(
      ctx: AppContext,
      expected: AppRoute,
      mode: AuthPage.Mode,
  ): IO[Routes[IO]] =
    Routes.one[IO] { case uri if sameTemplate(AppRoute.parse(uri), expected) => uri } { location =>
      AuthPage(ctx, mode, location)
    }

  private def protectedStatic(
      ctx: AppContext,
      expected: AppRoute,
  )(page: => Resource[IO, HtmlElement[IO]]): IO[Routes[IO]] =
    staticRoute(expected)(requireAuth(ctx)(page))

  private def sameTemplate(actual: AppRoute, expected: AppRoute): Boolean =
    (actual, expected) match
      case (AppRoute.Login(_), AppRoute.Login(_)) => true
      case _ => actual == expected

  private def routeView[A](
      value: Signal[IO, A],
  )(page: A => Resource[IO, HtmlElement[IO]]): Resource[IO, HtmlElement[IO]] =
    div(cls := "route-view", value.map(page)).widen

  private def requireAuth(
      ctx: AppContext,
  )(page: => Resource[IO, HtmlElement[IO]]): Resource[IO, HtmlElement[IO]] =
    div(
      styleAttr := "display: contents",
      children <-- ctx.store.authStatus.map {
        case AuthStatus.Checking =>
          List(
            mainTag(
              cls := "session-check",
              aria.live := "polite",
              aria.busy := true,
              span(cls := "session-check__spinner", aria.hidden := true),
              "Checking your session…",
            ),
          )
        case AuthStatus.Authenticated => List(page)
        case AuthStatus.Anonymous => List(redirectToLogin(ctx))
      },
    ).widen

  private def redirectToLogin(ctx: AppContext): Resource[IO, HtmlElement[IO]] =
    Resource.eval {
      IO.cede *> ctx.navigator.location.get.flatMap { current =>
        val returnTo = AppRoute.safeReturnPath(current)
        ctx.navigator.replace(AppRoute.Login(Some(returnTo)))
      }
    } *> div(cls := "route-redirect").widen

  private object CoursePath:
    def unapply(uri: Uri): Option[String] = AppRoute.parse(uri) match
      case AppRoute.Course(slug) => Some(slug)
      case _ => None

  private object WatchLessonPath:
    def unapply(uri: Uri): Option[(String, String)] =
      val parts = AppRoute.normalizedPath(uri).split('/').filter(_.nonEmpty).toList
      parts match
        case "watch" :: slug :: lessonId :: Nil => Some(slug -> lessonId)
        case _ => None

  private object WatchCoursePath:
    def unapply(uri: Uri): Option[String] =
      val parts = AppRoute.normalizedPath(uri).split('/').filter(_.nonEmpty).toList
      parts match
        case "watch" :: slug :: Nil => Some(slug)
        case _ => None
