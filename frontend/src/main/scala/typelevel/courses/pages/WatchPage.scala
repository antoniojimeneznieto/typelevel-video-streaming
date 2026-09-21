package typelevel.courses.pages

import scala.concurrent.duration.{DurationInt, DurationLong}
import scala.scalajs.js

import calico.frp.given
import calico.html.io.{*, given}
import calico.syntax.*
import cats.effect.{IO, Ref, Resource}
import cats.kernel.Eq
import cats.syntax.all.*
import fs2.concurrent.{Signal, SignallingRef}
import fs2.dom.{HtmlElement, HtmlInputElement, HtmlVideoElement, Node}
import fs2.Stream
import org.scalajs.dom
import org.typelevel.video.streaming.backend.playback.api.{
  CourseNotFoundError,
  InvalidPlaybackProgressError,
  PlaybackUnavailableError,
  VideoNotFoundError,
}
import org.typelevel.video.streaming.backend.playback.domain.PlaybackProgress
import smithy4s.http.RawErrorResponse
import typelevel.courses.AppContext
import typelevel.courses.routing.AppRoute
import typelevel.courses.state.RemoteStateStatus
import typelevel.courses.ui.{CourseView, Icon, Icons, LessonView}

object WatchPage:
  private val ChromeIdle         = 2800.millis
  private val ProgressCheckpoint = 10.seconds

  private enum SourceState:
    case Loading, Ready
    case Failed(message: String)

  final private case class RefreshSnapshot(position: Double, playing: Boolean)

  final private case class SharedState(
      isMuted: SignallingRef[IO, Boolean],
      captionsOn: SignallingRef[IO, Boolean],
      autoNext: SignallingRef[IO, Boolean],
      autoplayTarget: Ref[IO, Option[(String, String)]],
  )

  final private case class PlayerRefs(
      currentTime: SignallingRef[IO, Double],
      duration: SignallingRef[IO, Double],
      isPlaying: SignallingRef[IO, Boolean],
      chromeVisible: SignallingRef[IO, Boolean],
      sourceState: SignallingRef[IO, SourceState],
      progressSaveError: SignallingRef[IO, Option[String]],
      metadataLoaded: Ref[IO, Boolean],
      restored: Ref[IO, Boolean],
      refreshSnapshot: Ref[IO, Option[RefreshSnapshot]],
      latestPosition: Ref[IO, Double],
      hasPlaybackActivity: Ref[IO, Boolean],
      suppressSeekSave: Ref[IO, Boolean],
      mediaRefreshAttempts: Ref[IO, Int],
      chromeActivity: SignallingRef[IO, Long],
      sourceRequests: SignallingRef[IO, (Long, Boolean)],
      stage: Ref[IO, Option[dom.Element]],
      autoPlayPending: Ref[IO, Boolean],
      progressWriter: ProgressWriter,
  )

  def apply(
      ctx: AppContext,
      route: Signal[IO, (String, String)],
  ): Resource[IO, HtmlElement[IO]] =
    for
      isMuted        <- SignallingRef[IO].of(false).toResource
      captionsOn     <- SignallingRef[IO].of(true).toResource
      autoNext       <- SignallingRef[IO].of(true).toResource
      autoplayTarget <- Ref[IO].of(Option.empty[(String, String)]).toResource
      shared          = SharedState(isMuted, captionsOn, autoNext, autoplayTarget)
      page           <- div(
                styleAttr := "display: contents",
                children[(String, String)] { (slug, lessonId) =>
                  renderRoute(ctx, shared, slug, lessonId).map(value => value: Node[IO])
                } <-- route.map(value => List(value)),
              )
    yield page

  private def renderRoute(
      ctx: AppContext,
      shared: SharedState,
      slug: String,
      lessonId: String,
  ): Resource[IO, HtmlElement[IO]] =
    div(
      styleAttr := "display: contents",
      ctx.catalog.course(slug).map {
        case None => notFound(ctx).map(value => value: Node[IO])
        case Some(course) =>
          val lessonIndex = course.lessons.indexWhere(_.id == lessonId)
          course.lessons.lift(lessonIndex) match
            case None => notFound(ctx).map(value => value: Node[IO])
            case Some(lesson) =>
              renderLesson(ctx, shared, course, lesson, lessonIndex).map(value => value: Node[IO])
      },
    )

  private def renderLesson(
      ctx: AppContext,
      shared: SharedState,
      course: CourseView,
      lesson: LessonView,
      lessonIndex: Int,
  ): Resource[IO, HtmlElement[IO]] =
    val courseId          = course.course.id.value.toString
    val nextLesson        = course.lessons.lift(lessonIndex + 1)
    val lessonProgress    = ctx.store.lessonProgress(courseId, lesson.id)
    val isStandaloneVideo = course.isVideo

    for
      currentTime          <- SignallingRef[IO].of(0.0).toResource
      duration             <- SignallingRef[IO].of(lesson.durationSeconds.toDouble).toResource
      isPlaying            <- SignallingRef[IO].of(false).toResource
      chromeVisible        <- SignallingRef[IO].of(true).toResource
      sourceState          <- SignallingRef[IO].of(SourceState.Loading).toResource
      progressSaveError    <- SignallingRef[IO].of(Option.empty[String]).toResource
      metadataLoaded       <- Ref[IO].of(false).toResource
      restored             <- Ref[IO].of(false).toResource
      refreshSnapshot      <- Ref[IO].of(Option.empty[RefreshSnapshot]).toResource
      latestPosition       <- Ref[IO].of(0.0).toResource
      hasPlaybackActivity  <- Ref[IO].of(false).toResource
      suppressSeekSave     <- Ref[IO].of(false).toResource
      mediaRefreshAttempts <- Ref[IO].of(0).toResource
      chromeActivity       <- SignallingRef[IO].of(0L).toResource
      sourceRequests       <- SignallingRef[IO].of(0L -> false).toResource
      stageRef             <- Ref[IO].of(Option.empty[dom.Element]).toResource
      progressSession      <- ctx.store.snapshot.map(_.accessToken).toResource
      persistProgress      <- ctx.store.lessonProgressSaver(courseId, lesson.id).toResource
      progressWriter       <- ProgressWriter.resource(
                          (position, keepalive) =>
                            ctx.store.snapshot.flatMap { current =>
                              IO.whenA(current.accessToken == progressSession) {
                                persistProgress(position, keepalive).void
                              }
                            },
                          error => progressSaveError.set(error.map(progressErrorMessage)),
                          ctx.store.runInBackground,
                          ctx.store.runLessonProgressWriter(courseId, lesson.id),
                        )
      shouldAutoPlay <-
        Resource.eval(
          shared.autoplayTarget
            .modify(target => (None, target.contains((course.course.slug.value, lesson.id)))),
        )
      autoPlayPending <- Ref[IO].of(shouldAutoPlay).toResource
      refs             = PlayerRefs(
               currentTime          = currentTime,
               duration             = duration,
               isPlaying            = isPlaying,
               chromeVisible        = chromeVisible,
               sourceState          = sourceState,
               progressSaveError    = progressSaveError,
               metadataLoaded       = metadataLoaded,
               restored             = restored,
               refreshSnapshot      = refreshSnapshot,
               latestPosition       = latestPosition,
               hasPlaybackActivity  = hasPlaybackActivity,
               suppressSeekSave     = suppressSeekSave,
               mediaRefreshAttempts = mediaRefreshAttempts,
               chromeActivity       = chromeActivity,
               sourceRequests       = sourceRequests,
               stage                = stageRef,
               autoPlayPending      = autoPlayPending,
               progressWriter       = progressWriter,
             )
      videoElement <- videoTag("Your browser does not support HTML video.")
      video         = rawVideo(videoElement)
      controller    = PlayerController(
                     ctx            = ctx,
                     course         = course,
                     lesson         = lesson,
                     nextLesson     = nextLesson,
                     lessonProgress = lessonProgress,
                     shared         = shared,
                     refs           = refs,
                     video          = video,
                   )
      _          <- setupVideo(video, course.thumbnail)
      _          <- if isStandaloneVideo then Resource.unit else setupCaptions(video)
      _          <- videoEvents(videoElement, controller)
      playerReady = (sourceState: Signal[IO, SourceState], ctx.store.progressStatus).mapN {
                      case (
                            SourceState.Ready,
                            RemoteStateStatus.Ready | RemoteStateStatus.Error,
                          ) =>
                        true
                      case _ => false
                    }
      stage <- buildStage(
                 ctx               = ctx,
                 course            = course,
                 lesson            = lesson,
                 lessonIndex       = lessonIndex,
                 videoElement      = videoElement,
                 controller        = controller,
                 refs              = refs,
                 playerReady       = playerReady,
                 isStandaloneVideo = isStandaloneVideo,
               )
      rawStage = rawElement(stage)
      _       <- Resource.eval(stageRef.set(Some(rawStage)))
      _       <- stageEvents(rawStage, controller, chromeVisible)
      _       <- keyboardShortcuts(controller)
      _       <- progressStatusLifecycle(ctx.store.progressStatus, controller)
      _       <- checkpointLifecycle(controller)
      _       <- pageLifecycle(controller)
      _       <- chromeLifecycle(refs)
      _       <- sourceLifecycle(refs, controller)
      page    <- div(
                cls := "watch-page",
                mainTag(
                  cls :=
                    (if isStandaloneVideo then "watch-layout watch-layout--standalone"
                     else "watch-layout"),
                  div(cls := "watch-main", stage),
                ),
              )
    yield page

  private def buildStage(
      ctx: AppContext,
      course: CourseView,
      lesson: LessonView,
      lessonIndex: Int,
      videoElement: HtmlVideoElement[IO],
      controller: PlayerController,
      refs: PlayerRefs,
      playerReady: Signal[IO, Boolean],
      isStandaloneVideo: Boolean,
  ): Resource[IO, HtmlElement[IO]] =
    sectionTag.withSelf { stage =>
      val rawStage = rawElement(stage)
      (
        cls <-- refs.chromeVisible.map { visible =>
          List("video-stage", if visible then "is-chrome-visible" else "is-chrome-hidden")
        },
        aria.label := s"Video player: ${lesson.title}",
        onPointerMove(controller.revealChrome),
        onPointerDown(controller.revealChrome),
        onPointerLeave(controller.scheduleChromeHide),
        videoElement,
        playerTop(ctx, course, lesson, isStandaloneVideo),
        children <-- (refs.sourceState: Signal[IO, SourceState], ctx.store.progressStatus).mapN {
          case (SourceState.Loading, _) => List(asNode(playbackLoading))
          case (SourceState.Ready, RemoteStateStatus.Loading | RemoteStateStatus.Idle) =>
            List(asNode(progressLoading))
          case (SourceState.Failed(message), _) =>
            List(asNode(playbackError(course, message, controller.beginSourceRequest(true))))
          case _ => Nil
        },
        Option.when(!isStandaloneVideo) {
          (playerReady, refs.currentTime: Signal[IO, Double], refs.duration: Signal[IO, Double])
            .mapN((ready, time, total) => Option.when(ready)(slidePhase(time, total)))
            .changes(using Eq.fromUniversalEquals)
            .map(_.map(phase => lessonSlide(course, lesson, lessonIndex, phase)))
        },
        (playerReady, refs.isPlaying: Signal[IO, Boolean], refs.currentTime: Signal[IO, Double])
          .mapN { (ready, playing, time) =>
            Option.when(ready && !playing && time == 0.0) {
              asNode(
                button(
                  cls := "lesson-slide__play",
                  typ := "button",
                  onClick(controller.togglePlayback),
                  aria.label := "Play video",
                  Icons(Icon.Play),
                ),
              )
            }
          },
        refs.progressSaveError.map(_.map { message =>
          asNode(
            p(
              cls := "playback-toast playback-toast--error",
              role := List("status"),
              message,
            ),
          )
        }),
        playerReady.map { ready =>
          Option.when(ready) {
            asNode(
              customVideoControls(
                stage        = rawStage,
                controller   = controller,
                refs         = refs,
                showCaptions = !isStandaloneVideo,
              ),
            )
          }
        },
      )
    }

  private def playerTop(
      ctx: AppContext,
      course: CourseView,
      lesson: LessonView,
      isStandaloneVideo: Boolean,
  ): Resource[IO, HtmlElement[IO]] =
    val backRoute = AppRoute.Course(course.course.slug.value)
    div(
      cls := "video-stage__top video-player-chrome",
      a.withSelf { self =>
        (
          cls := "video-stage__back",
          href := ctx.navigator.href(backRoute),
          aria.label := s"Back to ${if isStandaloneVideo then "video" else "course"}",
          ctx.navigator.intercept(self, backRoute),
          Icons(Icon.ArrowLeft),
        )
      },
      div(
        cls := "video-stage__title",
        Option.when(course.course.title.value != lesson.title)(span(course.course.title.value)),
        h1(lesson.title),
      ),
    )

  private def playbackLoading: Resource[IO, HtmlElement[IO]] =
    div(
      cls := "playback-state",
      role := List("status"),
      aria.live := "polite",
      span(cls := "session-check__spinner", aria.hidden := true),
      strong("Preparing secure playback…"),
      small("The video URL is requested from the playback service."),
    )

  private def progressLoading: Resource[IO, HtmlElement[IO]] =
    div(
      cls := "playback-state",
      role := List("status"),
      aria.live := "polite",
      span(cls := "session-check__spinner", aria.hidden := true),
      strong("Restoring your progress…"),
    )

  private def playbackError(
      course: CourseView,
      message: String,
      retry: IO[Unit],
  ): Resource[IO, HtmlElement[IO]] =
    div(
      cls := "playback-state playback-state--error",
      role := List("alert"),
      strong("Playback is unavailable"),
      p(message),
      button(
        typ := "button",
        cls := "button button--light",
        onClick(retry),
        Icons(Icon.RefreshCw),
        "Try again",
      ),
      course.source.map { source =>
        a(
          href := source.url,
          target := "_blank",
          rel := List("noreferrer"),
          Icons(Icon.ExternalLink),
          "View the original",
        )
      },
    )

  private def lessonSlide(
      course: CourseView,
      lesson: LessonView,
      lessonIndex: Int,
      phase: Int,
  ): Resource[IO, Node[IO]] =
    asNode(
      div(
        cls := s"lesson-slide lesson-slide--phase-$phase",
        div(
          cls := "lesson-slide__top",
          aria.hidden := true,
          span(s"TYPELEVEL LEARNING CENTER / ${course.course.topic.value.toUpperCase}"),
          span(s"LESSON ${pad2(lessonIndex + 1)}"),
        ),
        phasePanel(course, lesson, phase),
        div(cls := "lesson-slide__shape lesson-slide__shape--one"),
        div(cls := "lesson-slide__shape lesson-slide__shape--two"),
      ),
    )

  private def phasePanel(
      course: CourseView,
      lesson: LessonView,
      phase: Int,
  ): Resource[IO, Node[IO]] =
    val safeTitle = lesson.title.replace("\"", "'")
    val demoCode  =
      s"""val lesson = Concept("$safeTitle")
         |lesson.explain *> lesson.practice""".stripMargin

    asNode(
      phase match
        case 0 =>
          div(
            cls := "lesson-slide__center lesson-slide__intro",
            aria.hidden := true,
            span(cls := "lesson-slide__marker", "01"),
            p("FOCUSED LESSON"),
            div(cls := "lesson-slide__display-title", lesson.title),
            span(cls := "lesson-slide__rule"),
            small(course.course.instructor.name.value),
          )
        case 1 =>
          div(
            cls := "lesson-slide__center lesson-slide__code",
            aria.hidden := true,
            p("UNDERSTAND. THEN PRACTICE."),
            pre(demoCode),
            small("Turn the explanation into a small, concrete exercise."),
          )
        case _ =>
          div(
            cls := "lesson-slide__center lesson-slide__takeaway",
            aria.hidden := true,
            p("THE TAKEAWAY"),
            div(
              cls := "lesson-slide__display-title",
              "Understand it.",
              br(()),
              span("Make it yours."),
            ),
            div(
              cls := "lesson-slide__steps",
              span("watch"),
              i(()),
              span("practice"),
              i(()),
              span("apply"),
            ),
          ),
    )

  private def customVideoControls(
      stage: dom.Element,
      controller: PlayerController,
      refs: PlayerRefs,
      showCaptions: Boolean,
  ): Resource[IO, HtmlElement[IO]] =
    val timeLabel = (refs.currentTime: Signal[IO, Double], refs.duration: Signal[IO, Double]).mapN {
      (time, total) => s"${formatTime(time)} / ${formatTime(total)}"
    }
    val timelineValue =
      (refs.currentTime: Signal[IO, Double], refs.duration: Signal[IO, Double]).mapN {
        (time, total) => math.min(time, math.max(0.0, total)).toString
      }
    val timelineStyle =
      (refs.currentTime: Signal[IO, Double], refs.duration: Signal[IO, Double]).mapN {
        (time, total) =>
          val progress = if total > 0.0 then (time / total) * 100.0 else 0.0
          s"--video-progress: $progress%"
      }

    for
      playButton <- button(
                      typ := "button",
                      onClick(controller.togglePlayback),
                      aria.label <-- refs.isPlaying.map(if _ then "Pause video" else "Play video"),
                      title <-- refs.isPlaying.map(if _ then "Pause (Space)" else "Play (Space)"),
                      refs.isPlaying.map(playing =>
                        asNode(Icons(if playing then Icon.Pause else Icon.Play)),
                      ),
                    )
      _ <- Resource.eval(
             IO.delay(rawElement(playButton).setAttribute("aria-keyshortcuts", "Space")),
           )
      controls <- div(
                    cls := "custom-video-controls video-player-chrome",
                    role := List("group"),
                    aria.label := "Playback controls",
                    playButton,
                    span(cls := "custom-video-controls__time", timeLabel),
                    input.withSelf { self =>
                      (
                        typ := "range",
                        minAttr := "0",
                        maxAttr <-- refs.duration.map(total => math.max(0.0, total).toString),
                        stepAttr := "0.05",
                        value <-- timelineValue,
                        aria.label := "Video timeline",
                        styleAttr <-- timelineStyle,
                        onInput(controller.seek(self)),
                      )
                    },
                    Option.when(showCaptions) {
                      button(
                        typ := "button",
                        cls <-- controller.shared.captionsOn.map(on =>
                          if on then List("is-active") else Nil,
                        ),
                        onClick(controller.toggleCaptions),
                        aria.label <-- controller.shared.captionsOn.map(
                          if _ then "Turn captions off" else "Turn captions on",
                        ),
                        Icons(Icon.Captions),
                      )
                    },
                    button(
                      typ := "button",
                      onClick(controller.toggleMuted),
                      aria.label <-- controller.shared.isMuted.map(if _ then "Unmute video"
                      else "Mute video"),
                      controller.shared.isMuted.map { muted =>
                        asNode(Icons(if muted then Icon.VolumeX else Icon.Volume2))
                      },
                    ),
                    button(
                      typ := "button",
                      onClick(toggleFullscreen(stage)),
                      aria.label := "Toggle fullscreen",
                      Icons(Icon.Maximize),
                    ),
                  )
    yield controls

  final private class PlayerController(
      ctx: AppContext,
      course: CourseView,
      lesson: LessonView,
      nextLesson: Option[LessonView],
      lessonProgress: Signal[IO, Option[PlaybackProgress]],
      val shared: SharedState,
      refs: PlayerRefs,
      video: dom.HTMLVideoElement,
  ):
    private def sourceReady: IO[Boolean] = refs.sourceState.get.map(_ == SourceState.Ready)

    private def playerReady: IO[Boolean] =
      (sourceReady, ctx.store.progressStatus.get).mapN { (ready, status) =>
        ready && (status == RemoteStateStatus.Ready || status == RemoteStateStatus.Error)
      }

    private def clampPosition(position: Double): Int =
      normalizedProgressPosition(position, lesson.durationSeconds)

    private def queueProgressSave(position: Double): IO[Unit] =
      refs.hasPlaybackActivity.get.ifM(refs.progressWriter.offer(clampPosition(position)), IO.unit)

    def beginSourceRequest(capturePlayback: Boolean): IO[Unit] =
      refs.sourceRequests.update { case (generation, _) => (generation + 1, capturePlayback) }

    // A single switchMap-owned loop owns both the fetch and its expiry timer. A new
    // request cancels the previous generation; page release cancels the entire loop.
    def sourceLoop(capturePlayback: Boolean): IO[Unit] =
      IO.whenA(capturePlayback)(captureForRefresh) *>
        refs.sourceState.set(SourceState.Loading) *>
        refs.metadataLoaded.set(false) *>
        refs.restored.set(false) *>
        refs.suppressSeekSave.set(true) *>
        refs.chromeVisible.set(true) *>
        refs.isPlaying.set(false) *>
        IO.delay {
          video.pause()
          video.removeAttribute("src")
          video.load()
        } *>
        requestSource

    private def requestSource: IO[Unit] =
      ctx.store
        .requestPlaybackUrl(course.course.id.value.toString, lesson.id)
        .attempt
        .flatMap {
          case Right(response) =>
            IO.delay {
              // The MinIO URL is already signed. Preserve it exactly and never add the identity JWT.
              video.setAttribute("src", response.url.value)
              video.load()
            } *>
              refs.sourceState.set(SourceState.Ready) *>
              IO.sleep(sourceRefreshDelayMillis(response.expiresIn.value).millis) *>
              IO.defer(sourceLoop(capturePlayback = true))
          case Left(error) =>
            refs.sourceState.set(SourceState.Failed(sourceErrorMessage(error)))
        }

    private def captureForRefresh: IO[Unit] =
      (refs.sourceState.get, refs.restored.get).tupled.flatMap {
        case (SourceState.Ready, true) =>
          val position = finiteOrZero(video.currentTime)
          for
            progress <- lessonProgress.get
            _        <- refs.refreshSnapshot.set(Some(RefreshSnapshot(position, !video.paused)))
            _        <- IO.whenA(position > 0.0 || !progress.exists(_.completed))(
                   queueProgressSave(position),
                 )
          yield ()
        case _ => IO.unit
      }

    def handleLoadedMetadata: IO[Unit] =
      refs.metadataLoaded.set(true) *>
        refs.suppressSeekSave.set(false) *>
        refs.duration.set(validDuration(video.duration, lesson.durationSeconds.toDouble)) *>
        (shared.captionsOn.get, shared.isMuted.get).tupled.flatMap { (captions, muted) =>
          IO.delay {
            video.muted = muted
            setCaptionModeUnsafe(video, captions)
          }
        } *>
        restoreProgress

    def restoreProgress: IO[Unit] =
      for
        loaded          <- refs.metadataLoaded.get
        snapshot        <- refs.refreshSnapshot.get
        alreadyRestored <- refs.restored.get
        status          <- ctx.store.progressStatus.get
        canRestore       = status == RemoteStateStatus.Ready || status == RemoteStateStatus.Error
        _ <- IO.whenA(loaded && (snapshot.nonEmpty || (!alreadyRestored && canRestore))) {
               for
                 progress  <- lessonProgress.get
                 mediaLimit = validDuration(video.duration, lesson.durationSeconds.toDouble)
                 position   = restoredPosition(
                              progress        = progress,
                              refreshPosition = snapshot.map(_.position),
                              lessonDuration  = lesson.durationSeconds,
                              mediaDuration   = mediaLimit,
                            )
                 shouldSeek = math.abs(video.currentTime - position) > 0.01
                 _         <- refs.suppressSeekSave.set(shouldSeek)
                 _         <- IO.whenA(shouldSeek)(IO.delay(video.currentTime = position))
                 _         <- refs.latestPosition.set(position)
                 _         <- refs.currentTime.set(position)
                 _         <- refs.refreshSnapshot.set(None)
                 _         <- refs.restored.set(true)
                 autoPlay  <- refs.autoPlayPending.getAndSet(false)
                 _         <- IO.whenA(snapshot.exists(_.playing) || autoPlay)(playVideo(video))
               yield ()
             }
      yield ()

    def handlePlay: IO[Unit] =
      refs.hasPlaybackActivity.set(true) *>
        refs.chromeVisible.set(true) *>
        refs.isPlaying.set(true) *>
        scheduleChromeHide

    def handlePause: IO[Unit] =
      refs.chromeVisible.set(true) *>
        refs.isPlaying.set(false) *>
        (sourceReady, refs.restored.get).tupled.flatMap { (ready, restored) =>
          IO.whenA(ready && restored)(queueProgressSave(video.currentTime))
        }

    def handleTimeUpdate: IO[Unit] =
      sourceReady.ifM(
        refs.latestPosition.set(finiteOrZero(video.currentTime)) *>
          refs.currentTime.set(finiteOrZero(video.currentTime)),
        IO.unit,
      )

    def handleSeeked: IO[Unit] =
      refs.suppressSeekSave.getAndSet(false).flatMap { suppressed =>
        if suppressed then IO.unit
        else
          (sourceReady, refs.restored.get).tupled.flatMap { (ready, restored) =>
            IO.whenA(ready && restored)(
              refs.hasPlaybackActivity.set(true) *> queueProgressSave(video.currentTime),
            )
          }
      }

    def handleEnded: IO[Unit] =
      refs.hasPlaybackActivity.set(true) *>
        refs.isPlaying.set(false) *>
        refs.chromeVisible.set(true) *>
        refs.latestPosition.set(lesson.durationSeconds.toDouble) *>
        refs.currentTime.set(lesson.durationSeconds.toDouble) *>
        queueProgressSave(lesson.durationSeconds.toDouble) *>
        shared.autoNext.get.flatMap { enabled =>
          (enabled, nextLesson) match
            case (true, Some(next)) =>
              shared.autoplayTarget.set(Some((course.course.slug.value, next.id))) *>
                ctx.navigator.go(AppRoute.Watch(course.course.slug.value, next.id))
            case _ => IO.unit
        }

    def handleMediaError: IO[Unit] =
      refs.sourceState.get.flatMap {
        case SourceState.Ready =>
          refs.mediaRefreshAttempts
            .modify { attempts =>
              if attempts < 1 then attempts + 1 -> true else attempts -> false
            }
            .flatMap { retry =>
              if retry then beginSourceRequest(capturePlayback = true)
              else
                captureForRefresh *>
                  refs.chromeVisible.set(true) *>
                  refs.isPlaying.set(false) *>
                  refs.sourceState.set(
                    SourceState.Failed(
                      "The secure video URL could not be loaded. Please request a new one.",
                    ),
                  )
            }
        case _ => IO.unit
      }

    def handleCanPlay: IO[Unit] = refs.mediaRefreshAttempts.set(0)

    def togglePlayback: IO[Unit] =
      playerReady.ifM(
        IO.delay(video.paused).flatMap(if _ then playVideo(video) else IO.delay(video.pause())),
        IO.unit,
      )

    def seek(input: HtmlInputElement[IO]): IO[Unit] =
      input.value.get.map(_.toDoubleOption.getOrElse(0.0)).flatMap { time =>
        val position = time.max(0.0).min(lesson.durationSeconds.toDouble)
        refs.hasPlaybackActivity.set(true) *>
          refs.suppressSeekSave.set(false) *>
          IO.delay(video.currentTime = position) *>
          refs.latestPosition.set(position) *>
          refs.currentTime.set(position)
      }

    def toggleCaptions: IO[Unit] =
      shared.captionsOn.modify(value => (!value, !value)).flatMap { enabled =>
        IO.delay(setCaptionModeUnsafe(video, enabled))
      }

    def toggleMuted: IO[Unit] =
      shared.isMuted.modify(value => (!value, !value)).flatMap { muted =>
        IO.delay(video.muted = muted)
      }

    def revealChrome: IO[Unit] =
      refs.chromeVisible.set(true) *> scheduleChromeHide

    def scheduleChromeHide: IO[Unit] =
      refs.chromeActivity.update(_ + 1)

    def handlePlaybackShortcut(event: dom.KeyboardEvent): IO[Unit] =
      playerReady.flatMap { ready =>
        val shouldToggle =
          event.code == "Space" &&
            ready &&
            !event.repeat &&
            !event.altKey &&
            !event.ctrlKey &&
            !event.metaKey &&
            !event.shiftKey &&
            !isPlaybackShortcutTarget(event.target)

        if shouldToggle then IO.delay(event.preventDefault()) *> revealChrome *> togglePlayback
        else IO.unit
      }

    def checkpoint: IO[Unit] =
      refs.isPlaying.get.ifM(
        refs.latestPosition.get.flatMap(queueProgressSave(_)),
        IO.unit,
      )

    def flushProgress: IO[Unit] =
      (refs.hasPlaybackActivity.get, refs.metadataLoaded.get, refs.latestPosition.get).tupled
        .flatMap { (active, loaded, position) =>
          IO.whenA(active && loaded)(refs.progressWriter.flush(clampPosition(position)))
        }

    def release: IO[Unit] =
      // Merely reopening a lesson may restore an older snapshot while its previous
      // writer finishes. Do not save that snapshot unless playback actually changed.
      (refs.hasPlaybackActivity.get, refs.latestPosition.get).tupled.flatMap { (active, position) =>
        refs.progressWriter.close(Option.when(active)(clampPosition(position)))
      }

  private def videoEvents(
      video: HtmlVideoElement[IO],
      controller: PlayerController,
  ): Resource[IO, Unit] =
    video.modify(
      (
        onClick(controller.togglePlayback),
        onPlay(controller.handlePlay),
        onPause(controller.handlePause),
        onLoadedMetadata(controller.handleLoadedMetadata),
        onCanPlay(controller.handleCanPlay),
        onTimeUpdate(controller.handleTimeUpdate),
        onSeeked(controller.handleSeeked),
        onEnded(controller.handleEnded),
        onError(controller.handleMediaError),
      ),
    )

  private def sourceLifecycle(refs: PlayerRefs, controller: PlayerController): Resource[IO, Unit] =
    (IO.cede *> refs.sourceRequests.discrete
      .switchMap { case (_, capturePlayback) =>
        Stream.exec(controller.sourceLoop(capturePlayback))
      }
      .compile
      .drain).background.void

  private def chromeLifecycle(refs: PlayerRefs): Resource[IO, Unit] =
    val activity =
      (refs.isPlaying: Signal[IO, Boolean], refs.chromeActivity: Signal[IO, Long]).tupled
    (IO.cede *> activity.discrete
      .switchMap { (playing, _) =>
        if !playing then Stream.empty
        else
          Stream.sleep_[IO](ChromeIdle) ++ Stream.eval(refs.stage.get.flatMap {
            case Some(stage) if focusVisibleInside(stage) => IO.unit
            case _ => refs.chromeVisible.set(false)
          })
      }
      .compile
      .drain).background.void

  private def stageEvents(
      stage: dom.Element,
      controller: PlayerController,
      chromeVisible: Signal[IO, Boolean],
  ): Resource[IO, Unit] =
    // Calico 0.2.3 has no bubbling focusin/focusout modifiers or data-* attribute builder.
    val focusIn =
      fs2.dom.events[IO, dom.Event](stage, "focusin").evalMap(_ => controller.revealChrome)
    val focusOut = fs2.dom.events[IO, dom.FocusEvent](stage, "focusout").evalMap { event =>
      val remainsInside = event.relatedTarget match
        case node: dom.Node => stage.contains(node)
        case _ => false
      IO.unlessA(remainsInside)(controller.scheduleChromeHide)
    }
    val chromeAttribute = chromeVisible.discrete.evalMap { visible =>
      IO.delay(stage.setAttribute("data-chrome-visible", visible.toString))
    }
    val streams = Stream(focusIn, focusOut, chromeAttribute).parJoinUnbounded

    (IO.cede *> streams.compile.drain).background.void

  private def keyboardShortcuts(controller: PlayerController): Resource[IO, Unit] =
    val stream = fs2.dom
      .events[IO, dom.KeyboardEvent](dom.window, "keydown")
      .evalMap(controller.handlePlaybackShortcut)
    (IO.cede *> stream.compile.drain).background.void

  private def progressStatusLifecycle(
      status: Signal[IO, RemoteStateStatus],
      controller: PlayerController,
  ): Resource[IO, Unit] =
    (IO.cede *> status.discrete
      .evalMap(_ => controller.restoreProgress)
      .compile
      .drain).background.void

  private def checkpointLifecycle(controller: PlayerController): Resource[IO, Unit] =
    (IO.cede *>
      Stream
        .awakeEvery[IO](ProgressCheckpoint)
        .evalMap(_ => controller.checkpoint)
        .compile
        .drain).background.void

  private def pageLifecycle(controller: PlayerController): Resource[IO, Unit] =
    val visibility = fs2.dom
      .events[IO, dom.Event](dom.document, "visibilitychange")
      .evalMap(_ => IO.whenA(dom.document.hidden)(controller.flushProgress))
    val pageHide = fs2.dom
      .events[IO, dom.Event](dom.window, "pagehide")
      .evalMap(_ => controller.flushProgress)

    Resource
      .make(
        (IO.cede *> visibility.merge(pageHide).compile.drain).start,
      )(fiber => fiber.cancel *> controller.release)
      .void

  private def setupVideo(
      video: dom.HTMLVideoElement,
      thumbnail: Option[String],
  ): Resource[IO, Unit] =
    Resource.make(
      IO.delay {
        video.setAttribute("playsinline", "")
        video.preload = "metadata"
        video.poster  = thumbnail.getOrElse("")
      },
    )(_ =>
      IO.delay {
        video.pause()
        video.removeAttribute("src")
        video.load()
      },
    )

  private def setupCaptions(video: dom.HTMLVideoElement): Resource[IO, Unit] =
    Resource.make {
      IO.delay {
        val track = dom.document.createElement("track").asInstanceOf[dom.HTMLTrackElement]
        track.kind    = "captions"
        track.src     = "/demo-captions.vtt"
        track.srclang = "en"
        track.label   = "English"
        track.setAttribute("default", "")
        val _ = video.appendChild(track)
        track
      }
    }(track => IO.delay(video.removeChild(track)).void).void

  private def playVideo(video: dom.HTMLVideoElement): IO[Unit] =
    // Playback state comes from media events. Awaiting play() here would leave
    // Calico's click/metadata listener unregistered while the media buffers.
    IO.delay {
      val result = video.play()
      if !js.isUndefined(result) then
        val _ = result.asInstanceOf[js.Promise[Unit]].`catch`[Unit]((_: Any) => ())
    }.attempt
      .void

  private def toggleFullscreen(stage: dom.Element): IO[Unit] =
    IO.defer {
      Option(dom.document.fullscreenElement) match
        case Some(_) => IO.fromPromise(IO.delay(dom.document.exitFullscreen()))
        case None => IO.fromPromise(IO.delay(stage.requestFullscreen()))
    }.attempt
      .void

  private def setCaptionModeUnsafe(video: dom.HTMLVideoElement, enabled: Boolean): Unit =
    if video.textTracks.length > 0 then
      video.textTracks(0).mode =
        if enabled then dom.TextTrackMode.showing else dom.TextTrackMode.hidden

  private[pages] def sourceErrorMessage(error: Throwable): String = error match
    case _: VideoNotFoundError | _: CourseNotFoundError =>
      "This video is uploaded, but its playback record is not available yet."
    case response: RawErrorResponse if response.code == 404 =>
      "This video is uploaded, but its playback record is not available yet."
    case _: PlaybackUnavailableError =>
      "Playback is still synchronizing your account. Please try again shortly."
    case response: RawErrorResponse if response.code == 503 =>
      "Playback is still synchronizing your account. Please try again shortly."
    case _ => "The video could not be prepared for playback."

  private[pages] def progressErrorMessage(error: Throwable): String = error match
    case error: InvalidPlaybackProgressError => error.message
    case error: IllegalArgumentException => error.getMessage
    case _ =>
      "Your progress could not be saved. We will try again at the next checkpoint."

  private[pages] def normalizedProgressPosition(position: Double, lessonDuration: Int): Int =
    math.floor(position).toInt.max(0).min(lessonDuration)

  private[pages] def sourceRefreshDelayMillis(expiresIn: Int): Long =
    val ttlMillis    = if expiresIn > 0 then expiresIn.toLong * 1000L else 900000L
    val safetyWindow = math.min(30000L, (ttlMillis.toDouble * 0.2).toLong)
    math.max(1000L, ttlMillis - safetyWindow)

  private[pages] def restoredPosition(
      progress: Option[PlaybackProgress],
      refreshPosition: Option[Double],
      lessonDuration: Int,
      mediaDuration: Double,
  ): Double =
    val requested = refreshPosition.getOrElse {
      if progress.exists(_.completed) then 0.0
      else progress.fold(0.0)(_.positionSeconds.value.toDouble)
    }
    requested.max(0.0).min(lessonDuration.toDouble).min(mediaDuration)

  private def isPlaybackShortcutTarget(target: dom.EventTarget | Null): Boolean =
    target match
      case element: dom.Element =>
        Option(
          element.closest(
            "input, textarea, select, button, a, [role='slider'], [contenteditable]:not([contenteditable='false'])",
          ),
        ).nonEmpty
      case _ => false

  private def focusVisibleInside(stage: dom.Element): Boolean =
    Option(dom.document.activeElement).exists { element =>
      stage.contains(element) &&
      (try element.matches(":focus-visible")
      catch case _: Throwable => true)
    }

  private def rawVideo(video: HtmlVideoElement[IO]): dom.HTMLVideoElement =
    video.asInstanceOf[dom.HTMLVideoElement]

  private def rawElement(element: HtmlElement[IO]): dom.Element =
    element.asInstanceOf[dom.Element]

  private def asNode[A <: Node[IO]](resource: Resource[IO, A]): Resource[IO, Node[IO]] =
    resource.map(value => value: Node[IO])

  private def validDuration(value: Double, fallback: Double): Double =
    if value.isFinite && value > 0.0 then value else fallback

  private def finiteOrZero(value: Double): Double =
    if value.isFinite && value >= 0.0 then value else 0.0

  private def slidePhase(currentTime: Double, duration: Double): Int =
    if currentTime < duration * 0.3 then 0
    else if currentTime < duration * 0.68 then 1
    else 2

  private def formatTime(value: Double): String =
    if !value.isFinite then "0:00"
    else
      val minutes = math.floor(value / 60.0).toInt
      val seconds = math.floor(value % 60.0).toInt
      f"$minutes%d:$seconds%02d"

  private def pad2(value: Int): String = f"$value%02d"

  private def notFound(ctx: AppContext): Resource[IO, HtmlElement[IO]] =
    val browseRoute = AppRoute.Browse
    mainTag(
      cls := "not-found not-found--embedded",
      div(cls := "not-found__code", "404"),
      span(cls := "not-found__icon", Icons(Icon.Compass)),
      p(cls := "eyebrow", "That path ends here"),
      h1("Let’s get you back to the useful part."),
      p("The page may have moved, or the video is still being prepared."),
      a.withSelf { self =>
        (
          cls := "button button--primary",
          href := ctx.navigator.href(browseRoute),
          ctx.navigator.intercept(self, browseRoute),
          Icons(Icon.ArrowLeft),
          "Browse videos",
        )
      },
    )
