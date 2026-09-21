package typelevel.courses

import scala.concurrent.duration.*

import calico.IOWebApp
import calico.router.Router
import cats.effect.{IO, Ref, Resource}
import fs2.dom.HtmlElement
import org.http4s.dom.FetchClientBuilder
import org.scalajs.dom
import typelevel.courses.api.{ApiConfig, CatalogApi, IdentityApi, PlaybackApi}
import typelevel.courses.routing.{AppRoute, Navigator}
import typelevel.courses.state.{AppStore, CatalogStore}

object Main extends IOWebApp:
  private val FragmentLookupFrames = 12

  def render: Resource[IO, HtmlElement[IO]] = for
    router <- Router(window).toResource
    client <- FetchClientBuilder[IO]
                .withRequestTimeout(30.seconds)
                .withoutStreamingRequests
                .resource
    config   = ApiConfig.browser
    catalog <- CatalogStore.resource(CatalogApi(config.catalogBaseUrl, client))
    store   <- AppStore.resource(
               catalog,
               IdentityApi(config.identityBaseUrl, client),
               PlaybackApi(config.playbackBaseUrl, client),
             )
    navigator     = Navigator(router)
    ctx           = AppContext(navigator, store, catalog)
    routes       <- Resource.eval(AppRoutes.build(ctx))
    app          <- router.dispatch(routes)
    previousPath <- Ref.of[IO, String]("").toResource
    _            <- navigator.location.discrete
           .evalMap { uri =>
             val path = AppRoute.normalizedPath(uri)
             previousPath.getAndSet(path).flatMap { previous =>
               uri.fragment match
                 case Some(fragment) =>
                   scrollToFragment(fragment).flatMap { found =>
                     if found || path == previous then IO.unit
                     else scrollToTop
                   }
                 case None if path != previous => scrollToTop
                 case None => IO.unit
             }
           }
           .compile
           .drain
           .background
  yield app

  private def scrollToFragment(fragment: String): IO[Boolean] =
    def find(remainingFrames: Int): IO[Boolean] =
      IO.delay(Option(dom.document.getElementById(fragment))).flatMap {
        case Some(element) => IO.delay(element.scrollIntoView()).map(_ => true)
        case None if remainingFrames > 0 =>
          nextAnimationFrame *> find(remainingFrames - 1)
        case None => IO.pure(false)
      }

    nextAnimationFrame *> find(FragmentLookupFrames)

  private def nextAnimationFrame: IO[Unit] =
    IO.async { callback =>
      IO.delay {
        val handle = dom.window.requestAnimationFrame(_ => callback(Right(())))
        Some(IO.delay(dom.window.cancelAnimationFrame(handle)))
      }
    }

  private def scrollToTop: IO[Unit] =
    IO.delay(dom.window.scrollTo(0, 0))
