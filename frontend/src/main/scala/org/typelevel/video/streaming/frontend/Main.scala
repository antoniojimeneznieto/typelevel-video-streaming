package org.typelevel.video.streaming.frontend

import calico.IOWebApp
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import org.http4s.dom.FetchClientBuilder
import org.scalajs.dom
import org.typelevel.video.streaming.frontend.api.{Profile, ProfileState, UserApi}
import org.typelevel.video.streaming.frontend.auth.{AuthClient, AuthSession, AuthStatus}
import org.typelevel.video.streaming.frontend.config.AppConfig
import org.typelevel.video.streaming.frontend.pages.{HomePage, SettingsPage}
import org.typelevel.video.streaming.frontend.routing.Route
import org.typelevel.video.streaming.frontend.ui.AppLayout

object Main extends IOWebApp {

  override def render =
    for {
      config     <- Resource.eval(AppConfig.load)
      httpClient <- FetchClientBuilder[IO].resource
      authClient  = AuthClient(config.keycloak, httpClient)
      userApi     = UserApi(config.userServiceBaseUrl, httpClient)
      route      <- Resource.eval(SignallingRef[IO].of(Route.current))
      auth       <- Resource.eval(SignallingRef[IO].of(AuthStatus.Checking))
      profile    <- Resource.eval(SignallingRef[IO].of(ProfileState.Idle))
      notice     <- Resource.eval(SignallingRef[IO].of(Option.empty[String]))
      actions     = buildActions(authClient, userApi, route, auth, profile, notice)
      _          <- Resource.eval(restoreSession(authClient, auth, notice))
      _          <- hashRouteSync(route, profile, actions)
      _          <- Resource.eval(syncRoute(route, profile, actions))
      page        = route.map {
                      case Route.Home     =>
                        HomePage.view(auth).map(element => element: fs2.dom.HtmlElement[IO])
                      case Route.Settings =>
                        SettingsPage
                          .view(auth, profile, actions)
                          .map(element => element: fs2.dom.HtmlElement[IO])
                    }
      app        <- AppLayout.view(auth, notice, actions, page)
    } yield app

  private def buildActions(
      authClient: AuthClient,
      userApi: UserApi,
      route: SignallingRef[IO, Route],
      auth: SignallingRef[IO, AuthStatus],
      profile: SignallingRef[IO, ProfileState],
      notice: SignallingRef[IO, Option[String]],
  ): AppActions = {

    def freshSession =
      authClient.currentSession.flatMap {
        case Some(session) => auth.set(AuthStatus.SignedIn(session)).as(Some(session))
        case None          => auth.set(AuthStatus.SignedOut).as(None)
      }

    def refreshSession =
      authClient.refreshSession.flatMap {
        case Some(session) => auth.set(AuthStatus.SignedIn(session)).as(Some(session))
        case None          => auth.set(AuthStatus.SignedOut).as(None)
      }

    def getProfile(session: AuthSession): IO[Profile] =
      userApi.getProfile(session.accessToken).recoverWith { case UserApi.Unauthorized =>
        refreshSession.flatMap {
          case Some(refreshed) => userApi.getProfile(refreshed.accessToken)
          case None            =>
            IO.raiseError(new RuntimeException("Your session expired. Sign in again."))
        }
      }

    def loadProfile =
      freshSession.flatMap {
        case Some(session) =>
          profile.set(ProfileState.Loading) *>
            getProfile(session).attempt.flatMap {
              case Right(value) =>
                profile.set(ProfileState.Loaded(value)) *>
                  notice.set(None)
              case Left(error)  =>
                profile.set(ProfileState.Failed(error.getMessage))
            }
        case None          =>
          profile.set(ProfileState.Failed("Sign in before loading your profile."))
      }

    AppActions(
      login = returnTo => authClient.login(returnTo.hash),
      logout = profile.set(ProfileState.Idle) *>
        auth.set(AuthStatus.SignedOut) *>
        authClient.logout,
      goHome = Route.navigate(Route.Home) *> route.set(Route.Home),
      goSettings = Route.navigate(Route.Settings) *> route.set(Route.Settings),
      configureTotp = authClient.configureTotp(Route.Settings.hash),
      refreshProfile = loadProfile,
      clearNotice = notice.set(None),
    )
  }

  private def restoreSession(
      authClient: AuthClient,
      auth: SignallingRef[IO, AuthStatus],
      notice: SignallingRef[IO, Option[String]],
  ): IO[Unit] =
    authClient.completeLoginOrRestore.attempt.flatMap {
      case Right(status) =>
        auth.set(status) *>
          (status match {
            case AuthStatus.Failed(message) => notice.set(Some(message))
            case _                          => notice.set(None)
          })
      case Left(error)   =>
        auth.set(AuthStatus.Failed(error.getMessage)) *>
          notice.set(Some(error.getMessage))
    }

  private def hashRouteSync(
      route: SignallingRef[IO, Route],
      profile: SignallingRef[IO, ProfileState],
      actions: AppActions,
  ): Resource[IO, Unit] =
    Resource.make {
      IO {
        val listener: dom.Event => Unit =
          _ => syncRoute(route, profile, actions).unsafeRunAndForget()
        dom.window.addEventListener("hashchange", listener)
        listener
      }
    }(listener => IO(dom.window.removeEventListener("hashchange", listener))).void

  private def syncRoute(
      route: SignallingRef[IO, Route],
      profile: SignallingRef[IO, ProfileState],
      actions: AppActions,
  ): IO[Unit] =
    for {
      next <- IO(Route.current)
      _    <- route.set(next)
      _    <-
        if next == Route.Settings then actions.refreshProfile
        else profile.set(ProfileState.Idle)
    } yield ()

}
