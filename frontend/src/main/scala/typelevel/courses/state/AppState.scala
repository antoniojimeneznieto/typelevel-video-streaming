package typelevel.courses.state

import java.util.UUID
import scala.concurrent.duration.*
import scala.scalajs.js

import calico.frp.given
import cats.effect.std.Supervisor
import cats.effect.{Deferred, Fiber, IO, Ref, Resource}
import cats.kernel.Eq
import cats.syntax.all.*
import fs2.concurrent.{Signal, SignallingRef}
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import org.scalajs.dom
import org.typelevel.video.streaming.backend.identity.api.{LoginInput, RegisterInput, UserResponse}
import org.typelevel.video.streaming.backend.identity.domain.{
  DisplayName,
  Email,
  NewPassword,
  Password,
}
import org.typelevel.video.streaming.backend.playback.api.{
  CourseNotFoundError,
  InvalidPlaybackProgressError,
  ListFavoritesInput,
  ListPlaybackProgressInput,
  PlaybackUnavailableError,
  PlaybackUrlResponse,
  VideoNotFoundError,
}
import org.typelevel.video.streaming.backend.playback.domain.{
  CourseId,
  Favorite,
  PageLimit,
  PageOffset,
  PlaybackProgress,
}
import smithy4s.http.RawErrorResponse
import smithy4s.time.Timestamp
import typelevel.courses.api.{IdentityApi, PlaybackApi}
import typelevel.courses.ui.CourseView

enum AuthStatus:
  case Checking, Authenticated, Anonymous

enum RemoteStateStatus:
  case Idle, Loading, Ready, Error

final case class StoredAuthSession(accessToken: String, expiresAt: Double)
    derives Decoder,
      Encoder.AsObject

final private[state] case class AppStateData(
    user: Option[UserResponse]                 = None,
    authStatus: AuthStatus                     = AuthStatus.Anonymous,
    accessToken: String                        = "",
    playbackProgress: Vector[PlaybackProgress] = Vector.empty,
    favorites: Vector[Favorite]                = Vector.empty,
    progressStatus: RemoteStateStatus          = RemoteStateStatus.Idle,
    favoritesStatus: RemoteStateStatus         = RemoteStateStatus.Idle,
    progressSyncError: Option[String]          = None,
    favoritesSyncError: Option[String]         = None,
    progressMutationError: Option[String]      = None,
    favoriteMutationError: Option[String]      = None,
    pendingFavoriteIds: Set[String]            = Set.empty,
)

final case class AppState private[state] (
    private[state] val data: AppStateData,
    progress: Map[String, Int],
):
  export data.{
    accessToken,
    authStatus,
    favoriteMutationError,
    favorites,
    favoritesStatus,
    favoritesSyncError,
    pendingFavoriteIds,
    playbackProgress,
    progressMutationError,
    progressStatus,
    progressSyncError,
    user,
  }

  val saved: Set[String]              = favorites.map(_.courseId.value.toString).toSet
  val recentCourseIds: Vector[String] = playbackProgress.map(_.courseId.value.toString).distinct
  val completedLessons: Set[String]   = playbackProgress.collect {
    case item if item.completed =>
      AppState.completedKey(item.courseId.value.toString, item.lessonId.value)
  }.toSet

  def progressError: Option[String]  = progressMutationError.orElse(progressSyncError)
  def favoritesError: Option[String] = favoriteMutationError.orElse(favoritesSyncError)
  def playbackError: Option[String]  = progressError.orElse(favoritesError)

  def playbackStatus: RemoteStateStatus =
    if progressStatus == RemoteStateStatus.Error || favoritesStatus == RemoteStateStatus.Error then
      RemoteStateStatus.Error
    else if progressStatus == RemoteStateStatus.Loading || favoritesStatus == RemoteStateStatus.Loading
    then RemoteStateStatus.Loading
    else if progressStatus == RemoteStateStatus.Ready && favoritesStatus == RemoteStateStatus.Ready
    then RemoteStateStatus.Ready
    else RemoteStateStatus.Idle

object AppState:
  given Eq[AppState] = Eq.fromUniversalEquals

  val authStorageKey     = "typelevel-courses-auth-v1"
  val learningStorageKey = "typelevel-courses-learning-state-v2"
  val legacyStorageKey   = "typelevel-courses-demo-state-v1"

  def anonymous: AppState = view(AppStateData(), Vector.empty)

  def completedKey(courseId: String, lessonId: String): String = s"$courseId:$lessonId"

  private[state] def signal(
      data: Signal[IO, AppStateData],
      courses: Signal[IO, Vector[CourseView]],
  ): Signal[IO, AppState] =
    (data, courses).mapN(view).changes

  private[state] def view(
      data: AppStateData,
      courses: Vector[CourseView],
  ): AppState =
    val items       = data.playbackProgress
    val percentages = courses.flatMap { view =>
      val courseId      = view.course.id.value
      val totalDuration = view.lessons.map(_.durationSeconds).sum
      if totalDuration <= 0 then None
      else
        val watchedDuration = view.lessons.map { lesson =>
          items
            .find(item => item.courseId.value == courseId && item.lessonId.value == lesson.id)
            .fold(0)(item =>
              if item.completed then lesson.durationSeconds
              else math.min(item.positionSeconds.value, lesson.durationSeconds),
            )
        }.sum
        if watchedDuration <= 0 then None
        else
          val allComplete = view.lessons.nonEmpty && view.lessons.forall { lesson =>
            items.exists(item =>
              item.courseId.value == courseId && item.lessonId.value == lesson.id && item.completed,
            )
          }
          val amount =
            if allComplete then 100
            else math.min(99, math.floor(watchedDuration.toDouble / totalDuration * 100).toInt)
          Some(courseId.toString -> amount)
    }.toMap

    AppState(data, percentages)

private object LocalAuthSessionStorage:
  def read(nowMillis: Double): IO[Option[StoredAuthSession]] =
    IO.delay(Option(dom.window.localStorage.getItem(AppState.authStorageKey)))
      .attempt
      .flatMap {
        case Right(Some(value)) =>
          decode[StoredAuthSession](value).toOption.filter(valid(_, nowMillis)) match
            case session @ Some(_) => IO.pure(session)
            case None => clear.as(None)
        case _ => IO.pure(None)
      }

  def write(session: StoredAuthSession): IO[Unit] =
    IO.delay(
      dom.window.localStorage.setItem(AppState.authStorageKey, session.asJson.noSpaces),
    ).attempt
      .void

  def clear: IO[Unit] =
    IO.delay(dom.window.localStorage.removeItem(AppState.authStorageKey)).attempt.void

  def removeLegacyLearningState: IO[Unit] =
    IO.delay {
      dom.window.localStorage.removeItem(AppState.learningStorageKey)
      dom.window.localStorage.removeItem(AppState.legacyStorageKey)
    }.attempt
      .void

  private def valid(session: StoredAuthSession, nowMillis: Double): Boolean =
    session.accessToken.nonEmpty && session.expiresAt.isFinite && session.expiresAt > nowMillis

final class AppStore private (
    private val ref: SignallingRef[IO, AppStateData],
    private val identity: IdentityApi,
    private val playback: PlaybackApi,
    private val catalog: CatalogStore,
    private val supervisor: Supervisor[IO],
    private val progressWriters: Ref[IO, Map[(String, String), Deferred[IO, Unit]]],
    private val expiryFiber: Ref[IO, Option[Fiber[IO, Throwable, Unit]]],
    private val syncFiber: Ref[IO, Option[Fiber[IO, Throwable, Unit]]],
):
  private def distinct[A](source: Signal[IO, A]): Signal[IO, A] =
    source.changes(using Eq.fromUniversalEquals)

  val signal: Signal[IO, AppState]                   = AppState.signal(ref, catalog.courses)
  val user: Signal[IO, Option[UserResponse]]         = distinct(signal.map(_.user))
  val authStatus: Signal[IO, AuthStatus]             = distinct(signal.map(_.authStatus))
  val progressStatus: Signal[IO, RemoteStateStatus]  = distinct(signal.map(_.progressStatus))
  val favoritesStatus: Signal[IO, RemoteStateStatus] = distinct(signal.map(_.favoritesStatus))
  val pendingFavoriteIds: Signal[IO, Set[String]]    = distinct(signal.map(_.pendingFavoriteIds))
  val saved: Signal[IO, Set[String]]                 = distinct(signal.map(_.saved))
  val progress: Signal[IO, Map[String, Int]]         = distinct(signal.map(_.progress))
  val completedLessons: Signal[IO, Set[String]]      = distinct(signal.map(_.completedLessons))
  private val playbackProgress: Signal[IO, Vector[PlaybackProgress]] =
    distinct(signal.map(_.playbackProgress))

  def snapshot: IO[AppState] = signal.get

  def runInBackground(effect: IO[Unit]): IO[Unit] =
    supervisor.supervise(effect).void

  def runLessonProgressWriter(courseId: String, lessonId: String)(effect: IO[Unit]): IO[Unit] =
    IO.uncancelable { _ =>
      val key = courseId -> lessonId
      for
        done     <- Deferred[IO, Unit]
        previous <-
          progressWriters.modify(current => current.updated(key, done) -> current.get(key))
        release = done.complete(()).void *> progressWriters.update { current =>
                    if current.get(key).contains(done) then current - key else current
                  }
        _ <- runInBackground((previous.traverse_(_.get) *> effect).guarantee(release))
               .onError(_ => release)
      yield ()
    }

  def lessonProgress(courseId: String, lessonId: String): Signal[IO, Option[PlaybackProgress]] =
    distinct(
      playbackProgress.map(
        _.find(item => item.courseId.value.toString == courseId && item.lessonId.value == lessonId),
      ),
    )

  def signIn(email: String, password: String): IO[Unit] =
    for
      login <- (validated(Email(email)), validated(Password(password)))
                 .mapN(LoginInput.apply)
                 .flatMap(identity.login)
      now    <- IO.realTime.map(_.toMillis.toDouble)
      session =
        StoredAuthSession(login.accessToken.value, now + login.expiresIn.value.toDouble * 1000)
      _       <- LocalAuthSessionStorage.write(session)
      current <- identity.currentUser(session.accessToken).attempt
      _       <- current.fold(
             error => clearSession *> IO.raiseError(error),
             user => authenticate(session, user),
           )
    yield ()

  def register(displayName: String, email: String, password: String): IO[Unit] =
    (
      validated(Email(email)),
      validated(NewPassword(password)),
      validated(DisplayName(displayName)),
    ).mapN(RegisterInput.apply).flatMap(identity.register) *> signIn(email, password)

  private def validated[A](value: Either[String, A]): IO[A] =
    IO.fromEither(value.leftMap(message => IllegalArgumentException(message)))

  def signOut: IO[Unit] = clearSession

  def requestPlaybackUrl(courseId: String, lessonId: String): IO[PlaybackUrlResponse] =
    authenticatedToken.flatMap { token =>
      PlaybackApi
        .withRetry(playback.playbackUrl(token, courseId, lessonId))
        .handleErrorWith(error => handlePlaybackFailure(token, error) *> IO.raiseError(error))
    }

  def lessonProgressSaver(
      courseId: String,
      lessonId: String,
  ): IO[(Int, Boolean) => IO[PlaybackProgress]] =
    authenticatedToken.map { token => (position, keepalive) =>
      saveLessonProgress(token, courseId, lessonId, position, keepalive)
    }

  private def saveLessonProgress(
      token: String,
      courseId: String,
      lessonId: String,
      positionSeconds: Int,
      keepalive: Boolean,
  ): IO[PlaybackProgress] =
    IO.defer {
      PlaybackApi
        .withRetry(
          playback.putProgress(
            token,
            courseId,
            lessonId,
            math.max(0, positionSeconds),
            keepalive,
          ),
        )
        .attempt
        .flatMap {
          case Right(result) =>
            updateIfCurrent(token) { current =>
              val updated = result +: current.playbackProgress.filter(item =>
                item.courseId != result.courseId || item.lessonId != result.lessonId,
              )
              current.copy(playbackProgress = updated, progressMutationError = None)
            }
              .as(result)
          case Left(error) =>
            handlePlaybackFailure(token, error) *>
              updateIfCurrent(token)(
                _.copy(
                  progressMutationError = Some(playbackErrorMessage(error)),
                ),
              ).unlessA(isUnauthorized(error)) *>
              IO.raiseError(error)
        }
    }

  def toggleSaved(courseId: String): IO[Unit] =
    runInBackground(updateSaved(courseId))

  private def updateSaved(courseId: String): IO[Unit] =
    ref
      .modify { current =>
        if current.authStatus != AuthStatus.Authenticated ||
          current.accessToken.isEmpty ||
          current.favoritesStatus != RemoteStateStatus.Ready ||
          current.pendingFavoriteIds.contains(courseId)
        then current -> Option.empty[(String, Option[Favorite])]
        else
          val existing   = current.favorites.find(_.courseId.value.toString == courseId)
          val optimistic = existing.fold(
            Favorite(
              CourseId(UUID.fromString(courseId)),
              Timestamp.fromEpochMilli(js.Date.now().toLong),
            ) +: current.favorites,
          )(_ => current.favorites.filterNot(_.courseId.value.toString == courseId))
          current.copy(
            pendingFavoriteIds = current.pendingFavoriteIds + courseId,
            favorites          = optimistic,
          ) -> Some(current.accessToken -> existing)
      }
      .flatMap {
        case None => IO.unit
        case Some((token, existing)) =>
          val mutation = existing.fold(
            PlaybackApi.withRetry(playback.putFavorite(token, courseId)).map(Some(_)),
          )(_ => PlaybackApi.withRetry(playback.deleteFavorite(token, courseId)).as(None))

          mutation.attempt
            .flatMap {
              case Right(created) =>
                updateIfCurrent(token) { current =>
                  val favorites = created.fold(current.favorites)(favorite =>
                    favorite +: current.favorites.filterNot(_.courseId.value.toString == courseId),
                  )
                  current.copy(favorites = favorites, favoriteMutationError = None)
                }
              case Left(error) =>
                handlePlaybackFailure(token, error) *>
                  updateIfCurrent(token) { current =>
                    val rolledBack = existing.fold(
                      current.favorites.filterNot(_.courseId.value.toString == courseId),
                    )(favorite =>
                      favorite +: current.favorites.filterNot(_.courseId.value.toString == courseId),
                    )
                    current.copy(
                      favorites             = rolledBack,
                      favoriteMutationError = Some(playbackErrorMessage(error)),
                    )
                  }.unlessA(isUnauthorized(error))
            }
            .guarantee(
              updateIfCurrent(token)(current =>
                current.copy(pendingFavoriteIds = current.pendingFavoriteIds - courseId),
              ),
            )
      }

  def refreshPlaybackState: IO[Unit] =
    ref.update(_.copy(progressMutationError = None, favoriteMutationError = None)) *>
      ref.get.flatMap { current =>
        IO.whenA(
          current.authStatus == AuthStatus.Authenticated && current.accessToken.nonEmpty,
        )(startPlaybackSync(current.accessToken))
      }

  private[state] def bootstrap(session: Option[StoredAuthSession]): IO[Unit] =
    session.fold(IO.unit) { stored =>
      identity.currentUser(stored.accessToken).attempt.flatMap {
        case Right(current) =>
          ref.get.flatMap(state =>
            IO.whenA(
              state.authStatus == AuthStatus.Checking &&
                state.accessToken == stored.accessToken,
            )(authenticate(stored, current)),
          )
        case Left(_) =>
          ref.get.flatMap(state =>
            IO.whenA(state.accessToken == stored.accessToken)(
              clearSession,
            ),
          )
      }
    }

  private def authenticate(session: StoredAuthSession, current: UserResponse): IO[Unit] =
    ref.set(
      AppStateData(
        user        = Some(current),
        authStatus  = AuthStatus.Authenticated,
        accessToken = session.accessToken,
      ),
    ) *>
      scheduleExpiry(session) *>
      startPlaybackSync(session.accessToken)

  private def authenticatedToken: IO[String] =
    ref.get.flatMap { current =>
      if current.authStatus == AuthStatus.Authenticated && current.accessToken.nonEmpty then
        IO.pure(current.accessToken)
      else IO.raiseError(IllegalStateException("Authentication is required."))
    }

  private def scheduleExpiry(session: StoredAuthSession): IO[Unit] =
    for
      now      <- IO.realTime.map(_.toMillis.toDouble)
      remaining = math.max(0, session.expiresAt - now).millis
      _ <- replaceFiber(expiryFiber, IO.sleep(remaining) *> expireIfCurrent(session.accessToken))
    yield ()

  private def expireIfCurrent(token: String): IO[Unit] =
    ref.get.flatMap { current =>
      IO.whenA(current.accessToken == token) {
        LocalAuthSessionStorage.clear *>
          ref.set(AppStateData()) *>
          cancelFiber(syncFiber)
      }
    }

  private def startPlaybackSync(token: String): IO[Unit] =
    replaceFiber(syncFiber, syncPlayback(token))

  private def syncPlayback(token: String): IO[Unit] =
    updateIfCurrent(token)(
      _.copy(
        progressStatus     = RemoteStateStatus.Loading,
        favoritesStatus    = RemoteStateStatus.Loading,
        progressSyncError  = None,
        favoritesSyncError = None,
      ),
    ) *>
      (syncProgress(token), syncFavorites(token)).parTupled.void

  private def syncProgress(token: String): IO[Unit] =
    collectPages(offset =>
      PlaybackApi
        .withRetry(
          playback.listProgress(
            token,
            ListPlaybackProgressInput(
              limit  = PageLimit.unsafeApply(100),
              offset = PageOffset.unsafeApply(offset),
            ),
          ),
        )
        .map(page => page.items -> page.total.value),
    ).attempt.flatMap {
      case Left(error) if isUnauthorized(error) => invalidateIfCurrent(token)
      case Left(error) =>
        updateIfCurrent(token)(
          _.copy(
            progressStatus    = RemoteStateStatus.Error,
            progressSyncError = Some(playbackErrorMessage(error)),
          ),
        )
      case Right(items) =>
        updateIfCurrent(token)(
          _.copy(
            playbackProgress  = items,
            progressStatus    = RemoteStateStatus.Ready,
            progressSyncError = None,
          ),
        )
    }

  private def syncFavorites(token: String): IO[Unit] =
    collectPages(offset =>
      PlaybackApi
        .withRetry(
          playback.listFavorites(
            token,
            ListFavoritesInput(
              limit  = PageLimit.unsafeApply(100),
              offset = PageOffset.unsafeApply(offset),
            ),
          ),
        )
        .map(page => page.items -> page.total.value),
    ).attempt.flatMap {
      case Left(error) if isUnauthorized(error) => invalidateIfCurrent(token)
      case Left(error) =>
        updateIfCurrent(token)(
          _.copy(
            favoritesStatus    = RemoteStateStatus.Error,
            favoritesSyncError = Some(playbackErrorMessage(error)),
          ),
        )
      case Right(items) =>
        updateIfCurrent(token)(
          _.copy(
            favorites          = items,
            favoritesStatus    = RemoteStateStatus.Ready,
            favoritesSyncError = None,
          ),
        )
    }

  private def collectPages[A](load: Int => IO[(List[A], Long)]): IO[Vector[A]] =
    def loop(offset: Int, items: Vector[A]): IO[Vector[A]] =
      load(offset).flatMap { (pageItems, total) =>
        val collected = items ++ pageItems
        if pageItems.isEmpty || collected.size >= total then IO.pure(collected)
        else loop(offset + pageItems.size, collected)
      }
    loop(0, Vector.empty)

  private def handlePlaybackFailure(token: String, error: Throwable): IO[Unit] =
    IO.whenA(isUnauthorized(error))(invalidateIfCurrent(token))

  private def invalidateIfCurrent(token: String): IO[Unit] =
    ref.get.flatMap(current =>
      IO.whenA(current.accessToken == token)(
        LocalAuthSessionStorage.clear *> ref.set(AppStateData()) *> cancelFiber(expiryFiber),
      ),
    )

  private def clearSession: IO[Unit] =
    LocalAuthSessionStorage.clear *>
      ref.set(AppStateData()) *>
      cancelFiber(expiryFiber) *>
      cancelFiber(syncFiber)

  private def updateIfCurrent(token: String)(update: AppStateData => AppStateData): IO[Unit] =
    ref.update(current => if current.accessToken == token then update(current) else current)

  private def replaceFiber(
      slot: Ref[IO, Option[Fiber[IO, Throwable, Unit]]],
      task: IO[Unit],
  ): IO[Unit] = for
    next     <- supervisor.supervise(task)
    previous <- slot.getAndSet(Some(next))
    _        <- previous.traverse_(_.cancel)
  yield ()

  private def cancelFiber(
      slot: Ref[IO, Option[Fiber[IO, Throwable, Unit]]],
  ): IO[Unit] =
    slot.getAndSet(None).flatMap(_.traverse_(_.cancel))

  private def isUnauthorized(error: Throwable): Boolean = error match
    case response: RawErrorResponse => response.code == 401
    case _ => false

  private def playbackErrorMessage(error: Throwable): String = error match
    case _: VideoNotFoundError | _: CourseNotFoundError =>
      "This content has not reached the playback service yet."
    case response: RawErrorResponse if response.code == 404 =>
      "This content has not reached the playback service yet."
    case _: PlaybackUnavailableError =>
      "Playback is still synchronizing. Please try again shortly."
    case response: RawErrorResponse if response.code == 503 =>
      "Playback is still synchronizing. Please try again shortly."
    case error: InvalidPlaybackProgressError => error.message
    case error: IllegalArgumentException => error.getMessage
    case _ => "The playback service could not be reached."

object AppStore:
  def resource(
      catalog: CatalogStore,
      identity: IdentityApi,
      playback: PlaybackApi,
  ): Resource[IO, AppStore] = for
    _       <- Resource.eval(LocalAuthSessionStorage.removeLegacyLearningState)
    now     <- Resource.eval(IO.realTime.map(_.toMillis.toDouble))
    session <- Resource.eval(LocalAuthSessionStorage.read(now))
    initial  = session.fold(AppStateData())(stored =>
                AppStateData(
                  authStatus  = AuthStatus.Checking,
                  accessToken = stored.accessToken,
                ),
              )
    ref             <- SignallingRef[IO].of(initial).toResource
    supervisor      <- Supervisor[IO]
    progressWriters <- Ref.of[IO, Map[(String, String), Deferred[IO, Unit]]](Map.empty).toResource
    expiryFiber     <- Ref.of[IO, Option[Fiber[IO, Throwable, Unit]]](None).toResource
    syncFiber       <- Ref.of[IO, Option[Fiber[IO, Throwable, Unit]]](None).toResource
    store            = AppStore(
              ref,
              identity,
              playback,
              catalog,
              supervisor,
              progressWriters,
              expiryFiber,
              syncFiber,
            )
    _ <- store.bootstrap(session).background
  yield store
