package typelevel.courses.pages

import calico.frp.given
import calico.html.io.{*, given}
import cats.effect.{IO, Resource}
import cats.kernel.Eq
import cats.syntax.all.*
import fs2.concurrent.{Signal, SignallingRef}
import fs2.dom.{HtmlElement, Node}
import fs2.Stream
import org.typelevel.video.streaming.backend.catalog.api.ListCoursesInput
import org.typelevel.video.streaming.backend.catalog.domain.{LearningPath, PageLimit, Topic}
import typelevel.courses.AppContext
import typelevel.courses.components.{Artwork, CourseCard, FavoriteButton, SiteHeader}
import typelevel.courses.routing.AppRoute
import typelevel.courses.state.AppState
import typelevel.courses.ui.CatalogPresentation.*
import typelevel.courses.ui.{CourseView, Icon, Icons}

object BrowsePage:
  private enum TopicStatus:
    case Loading, Ready, Error

  final private case class TopicRequest(
      topic: String,
      items: Vector[CourseView],
      status: TopicStatus,
  )

  def apply(ctx: AppContext): Resource[IO, HtmlElement[IO]] = for
    activeTopic  <- SignallingRef[IO].of("All topics").toResource
    topicRequest <- SignallingRef[IO].of(Option.empty[TopicRequest]).toResource
    _            <- topicRequests(ctx, activeTopic, topicRequest).background.void
    page         <- div(
              cls := "app-page",
              a(cls := "skip-link", href := "#browse-content", "Skip to content"),
              SiteHeader.AppHeader(ctx),
              content(ctx, activeTopic, topicRequest),
            ).widen
  yield page

  private def topicRequests(
      ctx: AppContext,
      activeTopic: SignallingRef[IO, String],
      topicRequest: SignallingRef[IO, Option[TopicRequest]],
  ): IO[Unit] =
    (activeTopic, ctx.catalog.courses)
      .mapN(_ -> _)
      .discrete
      .switchMap {
        case ("All topics", _) => Stream.eval(topicRequest.set(None))
        case (topic, courses) =>
          val local = coursesForTopic(courses, topic)
          Stream.eval(
            topicRequest.set(Some(TopicRequest(topic, local, TopicStatus.Loading))) *>
              IO.fromEither(Topic(topic).leftMap(new IllegalArgumentException(_)))
                .flatMap { value =>
                  ctx.catalog.queryCourses(
                    ListCoursesInput(topic = Some(value), limit = PageLimit.unsafeApply(100)),
                  )
                }
                .attempt
                .flatMap {
                  case Right(items) =>
                    topicRequest.set(Some(TopicRequest(topic, items, TopicStatus.Ready)))
                  case Left(_) =>
                    topicRequest.set(Some(TopicRequest(topic, local, TopicStatus.Error)))
                },
          )
      }
      .compile
      .drain

  private def content(
      ctx: AppContext,
      activeTopic: SignallingRef[IO, String],
      topicRequest: SignallingRef[IO, Option[TopicRequest]],
  ): Resource[IO, HtmlElement[IO]] =
    val courses  = ctx.catalog.courses
    val featured = courses
      .map(items => items.find(_.featured).orElse(items.headOption))
      .changes(using Eq.fromUniversalEquals)
    val continueCourses = (courses, ctx.store.progress)
      .mapN { (items, progress) =>
        items.filter { course =>
          val amount = progress.getOrElse(course.course.id.value.toString, 0)
          amount > 0 && amount < 100 && course.lessons.nonEmpty
        }
      }
      .changes(using Eq.fromUniversalEquals)
    val filteredCourses = (courses, activeTopic, topicRequest)
      .mapN { (items, topic, request) =>
        if topic == "All topics" then items
        else
          request
            .filter(_.topic == topic)
            .fold(coursesForTopic(items, topic))(_.items)
      }
      .changes(using Eq.fromUniversalEquals)
    val currentRequest = (activeTopic: Signal[IO, String], topicRequest).mapN { (topic, request) =>
      request.filter(_.topic == topic)
    }

    def selectTopic(next: String): IO[Unit] =
      courses.get.flatMap { items =>
        val request = Option.unless(next == "All topics")(
          TopicRequest(next, coursesForTopic(items, next), TopicStatus.Loading),
        )
        topicRequest.set(request) *> activeTopic.set(next)
      }

    div(
      mainTag(
        idAttr := "browse-content",
        sectionTag(
          cls := "browse-welcome app-shell",
          div(
            p(cls := "eyebrow", "Your learning space"),
            h1(ctx.store.signal.map(welcome).changes),
          ),
        ),
        featured.map(_.map(course => featuredHero(ctx, course))),
        continueSection(ctx, continueCourses),
        sectionTag(
          cls := "topic-filter-section app-shell",
          div(
            cls := "app-section__heading app-section__heading--topics",
            div(
              p(cls := "eyebrow", "Explore the library"),
              h2("Learn by topic"),
            ),
            span(
              aria.live := "polite",
              (currentRequest, filteredCourses).mapN { (request, items) =>
                request match
                  case Some(value) if value.status == TopicStatus.Loading =>
                    s"Loading ${value.topic}…"
                  case _ => s"${items.size} library items"
              },
            ),
          ),
          div(
            cls := "topic-chips",
            role := List("group"),
            aria.label := "Filter courses by topic",
            children[String] { item =>
              button(
                typ := "button",
                cls <-- activeTopic.map(topic => Option.when(topic == item)("is-active").toList),
                aria.pressed <-- activeTopic.map(topic => (topic == item).toString),
                onClick(selectTopic(item)),
                item,
              ).map(value => value: Node[IO])
            } <-- ctx.catalog.topics.map(_.toList),
          ),
          p(
            cls := "topic-filter-message",
            hidden <-- currentRequest.map(!_.exists(_.status == TopicStatus.Error)),
            role := List("alert"),
            "The catalog API could not apply this filter. Showing locally cached results.",
          ),
          CourseCard.grid(
            ctx,
            filteredCourses.map(_.take(8)),
            className = "course-grid course-grid--four",
            compact   = true,
          ),
          p(
            cls := "topic-filter-message",
            hidden <-- filteredCourses.map(_.nonEmpty),
            "No content is available for this topic yet.",
          ),
        ),
        ctx.catalog.learningPaths.map(pathsSection(ctx, _)),
      ),
      footerTag(
        cls := "app-footer",
        div(
          cls := "app-shell",
          span("Typelevel Learning Center"),
          div(
            a(
              href := "https://typelevel.org/",
              target := "_blank",
              rel := List("noreferrer"),
              "Typelevel.org",
            ),
          ),
        ),
      ),
    ).widen

  private def coursesForTopic(courses: Vector[CourseView], topic: String): Vector[CourseView] =
    courses.filter(_.course.topic.value == topic)

  private def welcome(state: AppState): String = state.user match
    case Some(user) =>
      val name = user.displayName.value
      s"Good to see you, ${name.split(' ').headOption.getOrElse(name)}."
    case _ => "What will you understand next?"

  private def featuredHero(
      ctx: AppContext,
      featured: CourseView,
  ): Resource[IO, HtmlElement[IO]] =
    val course  = featured.course
    val watch   = AppRoute.Watch(course.slug.value, "lesson-1")
    val details = AppRoute.Course(course.slug.value)
    val format  = featured.formatLabel.toLowerCase

    sectionTag(
      cls := "app-shell featured-course-hero",
      img(
        src := featured.thumbnail.getOrElse("/learning-network.webp"),
        alt := "",
        aria.hidden := true,
      ),
      div(cls := "featured-course-hero__veil"),
      div(
        cls := "featured-course-hero__content",
        span(
          cls := "featured-badge",
          span(()),
          s" Featured $format",
        ),
        p(
          cls := "eyebrow",
          s"${course.topic.value}${if featured.isNew then " · New" else ""}",
        ),
        h2(course.title.value),
        p(featured.shortDescription),
        div(
          cls := "featured-course-hero__facts",
          featured.rating.map(value => span(Icons(Icon.Star), s" $value")),
          span(course.level.label),
          span(
            if featured.lessonCount == 1 then "1 video"
            else s"${featured.lessonCount} lessons",
          ),
          span(featured.duration),
        ),
        div(
          cls := "hero-actions",
          a.withSelf { self =>
            (
              cls := "button button--light",
              href := ctx.navigator.href(watch),
              ctx.navigator.intercept(self, watch),
              Icons(Icon.Play),
              s" Play $format",
            )
          },
          a.withSelf { self =>
            (
              cls := "button button--glass",
              href := ctx.navigator.href(details),
              ctx.navigator.intercept(self, details),
              "More details",
            )
          },
          FavoriteButton.featured(ctx, featured),
        ),
      ),
      Option.unless(featured.isVideo)(
        div(
          cls := "featured-course-hero__code",
          aria.hidden := true,
          span("def program: IO[Unit] ="),
          strong("  learn.start"),
          span("    .flatMap(_.join)"),
        ),
      ),
    ).widen

  private def continueSection(
      ctx: AppContext,
      courses: Signal[IO, Vector[CourseView]],
  ): Resource[IO, HtmlElement[IO]] =
    sectionTag(
      cls := "app-section app-shell",
      hidden <-- courses.map(_.isEmpty),
      div(
        cls := "app-section__heading",
        div(p(cls := "eyebrow", "Pick up where you left off"), h2("Continue learning")),
        a.withSelf { self =>
          (
            href := ctx.navigator.href(AppRoute.MyLearning),
            ctx.navigator.intercept(self, AppRoute.MyLearning),
            "View my learning ",
            Icons(Icon.ArrowRight),
          )
        },
      ),
      div(
        cls := "continue-grid",
        children[String] { id =>
          courses.get.toResource
            .flatMap { items =>
              items.find(_.course.id.value.toString == id) match
                case None => div(())
                case Some(initial) =>
                  val course = courses
                    .map(_.find(_.course.id.value.toString == id).getOrElse(initial))
                    .changes(using Eq.fromUniversalEquals)
                  continueCard(ctx, course)
            }
            .map(value => value: Node[IO])
        } <-- courses.map(_.map(_.course.id.value.toString).toList),
      ),
    ).widen

  private def continueCard(
      ctx: AppContext,
      course: Signal[IO, CourseView],
  ): Resource[IO, HtmlElement[IO]] =
    val amount = (course, ctx.store.progress).mapN { (course, progress) =>
      progress.getOrElse(course.course.id.value.toString, 0)
    }.changes
    val nextLesson = (course, amount)
      .mapN { (course, amount) =>
        val nextIndex = math.min(
          course.lessons.size - 1,
          math.floor(amount.toDouble / 100 * course.lessons.size).toInt,
        )
        course.lessons(nextIndex)
      }
      .changes(using Eq.fromUniversalEquals)
    val destination = (course, nextLesson).mapN { (course, lesson) =>
      AppRoute.Watch(course.course.slug.value, lesson.id)
    }
    articleTag(
      cls := "continue-card",
      a.withSelf { self =>
        (
          cls := "continue-card__art",
          href <-- destination.map(ctx.navigator.href),
          aria.label <-- course.map(value => s"Continue ${value.course.title.value}"),
          ctx.navigator.intercept(self, destination.get.map(_.uri)),
          course
            .map(value => (value.artwork, value.artLabel, value.thumbnail))
            .changes(using Eq.fromUniversalEquals)
            .map { (artwork, label, thumbnail) => Artwork(artwork, label, thumbnail = thumbnail) },
          span(cls := "continue-card__play", Icons(Icon.Play)),
        )
      },
      div(
        cls := "continue-card__body",
        p(cls := "eyebrow eyebrow--small", course.map(_.course.title.value)),
        h3(nextLesson.map(_.title)),
        div(
          cls := "continue-card__bottom",
          span(amount.map(value => s"$value% complete")),
          span(Icons(Icon.Clock), nextLesson.map(value => s" ${value.duration}")),
        ),
      ),
      div(
        cls := "progress-bar",
        role := List("progressbar"),
        aria.label <-- course.map(value => s"${value.course.title.value} progress"),
        aria.valueMin := 0,
        aria.valueMax := 100,
        aria.valueNow <-- amount.map(_.toDouble),
        span(styleAttr <-- amount.map(value => s"width: $value%")),
      ),
    ).widen

  private def pathsSection(
      ctx: AppContext,
      learningPaths: Vector[LearningPath],
  ): Resource[IO, HtmlElement[IO]] =
    sectionTag(
      cls := "app-section app-section--lavender",
      div(
        cls := "app-shell",
        div(
          cls := "app-section__heading",
          div(p(cls := "eyebrow", "Curated journeys"), h2("Follow a learning path")),
          a.withSelf { self =>
            (
              href := ctx.navigator.href(AppRoute.Paths),
              ctx.navigator.intercept(self, AppRoute.Paths),
              "See all paths ",
              Icons(Icon.ArrowRight),
            )
          },
        ),
        div(
          cls := "browse-path-grid",
          learningPaths.zipWithIndex.toList.map { case (learningPath, index) =>
            val destination = AppRoute.Paths.uri.withFragment(learningPath.id.value)
            a.withSelf { self =>
              (
                cls := s"browse-path-card browse-path-card--${learningPath.tone.cssName}",
                href := ctx.navigator.href(destination),
                ctx.navigator.intercept(self, destination),
                span(cls := "browse-path-card__icon", Icons(Icon.Compass)),
                div(
                  span(
                    cls := "eyebrow eyebrow--small",
                    f"Path ${index + 1}%02d · ${learningPath.level.label}",
                  ),
                  h3(learningPath.title.value),
                  p(learningPath.description.value),
                  span(
                    cls := "browse-path-card__meta",
                    s"${learningPath.courseIds.size} courses · ${learningPath.timeLabel.value}",
                  ),
                ),
                Icons(Icon.ChevronRight, className = "browse-path-card__arrow"),
              )
            }
          },
        ),
      ),
    ).widen
