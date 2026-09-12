package typelevel.courses.pages

import calico.html.io.{*, given}
import cats.effect.{IO, Resource}
import cats.kernel.Eq
import cats.syntax.all.*
import fs2.concurrent.Signal
import fs2.dom.HtmlElement
import org.scalajs.dom
import typelevel.courses.AppContext
import typelevel.courses.components.{Artwork, CourseCard, FavoriteButton, SiteHeader}
import typelevel.courses.domain.{Course, CourseFormat}
import typelevel.courses.routing.AppRoute
import typelevel.courses.state.AppState
import typelevel.courses.ui.{Icon, Icons}

object CoursePage:
  def apply(ctx: AppContext, slug: String): Resource[IO, HtmlElement[IO]] =
    div(
      cls := "route-view",
      ctx.catalog.course(slug).map {
        case None => NotFoundPage(ctx, embedded = true)
        case Some(course) =>
          div(
            cls := "app-page detail-page",
            SiteHeader.AppHeader(ctx),
            content(ctx, course)
          ).widen
      }
    ).widen

  private def content(
      ctx: AppContext,
      course: Course
  ): Resource[IO, HtmlElement[IO]] =
    val isVideo        = course.format == CourseFormat.Video
    val courseProgress = ctx.store.progress.map(_.getOrElse(course.id, 0)).changes
    val related        = ctx.catalog.courses
      .map { courses =>
        courses
          .filter { item =>
            item.id != course.id && (
              item.topic == course.topic || item.technologies.exists(course.technologies.contains)
            )
          }
          .take(3)
      }
      .changes(using Eq.fromUniversalEquals)
    val watch = courseProgress
      .map { progress =>
        val nextLessonIndex =
          if course.lessons.isEmpty || progress >= 100 then 0
          else
            math.min(
              course.lessons.size - 1,
              math.floor(progress.toDouble / 100 * course.lessons.size).toInt
            )
        AppRoute.Watch(course.slug, course.lessons.lift(nextLessonIndex).fold("lesson-1")(_.id))
      }
      .changes(using Eq.fromUniversalEquals)

    val startLabel = courseProgress.map { progress =>
      if progress >= 100 then if isVideo then "Watch again" else "Review course"
      else if progress > 0 then s"Resume ${if isVideo then "video" else "course"} · $progress%"
      else s"Start ${if isVideo then "video" else "course"}"
    }

    mainTag(
      sectionTag(
        cls := "detail-hero",
        div(cls := "detail-hero__wash", aria.hidden := true),
        div(
          cls := "app-shell",
          a.withSelf { self =>
            (
              cls := "back-link",
              href := ctx.navigator.href(AppRoute.Browse),
              ctx.navigator.intercept(self, AppRoute.Browse),
              Icons(Icon.ArrowLeft),
              " Back to browse"
            )
          },
          div(
            cls := "detail-hero__grid",
            div(
              cls := "detail-hero__copy",
              div(
                cls := "detail-tags",
                span(course.format.label),
                Option.when(course.isNew)(span(cls := "is-new", "New")),
                span(course.level.label)
              ),
              p(cls := "eyebrow", course.topic),
              h1(course.title),
              p(cls := "detail-hero__description", course.description),
              div(
                cls := "detail-hero__facts",
                course.rating.zip(course.students).map { (rating, students) =>
                  span(
                    Icons(Icon.Star),
                    " ",
                    strong(rating.toString),
                    s" ($students learners)"
                  )
                },
                span(
                  Icons(Icon.ListVideo),
                  if course.lessonCount == 1 then " 1 video"
                  else s" ${course.lessonCount} lessons"
                ),
                span(Icons(Icon.Clock), s" ${course.duration}"),
                span(Icons(Icon.Globe), " English")
              ),
              div(
                cls := "detail-instructor",
                span(course.instructor.initials),
                p(
                  small(if isVideo then "Presented by" else "Created and taught by"),
                  strong(course.instructor.name),
                  em(course.instructor.role)
                )
              ),
              div(
                cls := "hero-actions",
                if course.lessons.nonEmpty then
                  a.withSelf { self =>
                    (
                      cls := "button button--primary button--large",
                      href <-- watch.map(ctx.navigator.href),
                      ctx.navigator.intercept(self, watch.get.map(_.uri)),
                      Icons(Icon.Play),
                      " ",
                      startLabel
                    )
                  }
                else
                  span(
                    cls := "button button--primary button--large is-disabled",
                    aria.disabled := true,
                    "Lessons coming soon"
                  )
                ,
                FavoriteButton.detail(ctx, course)
              )
            ),
            div(
              cls := "detail-hero__art-wrap",
              Artwork(course.artwork, course.artLabel, thumbnail = course.thumbnail),
              Option.when(course.lessons.nonEmpty) {
                a.withSelf { self =>
                  (
                    cls := "detail-art-play",
                    href <-- watch.map(ctx.navigator.href),
                    ctx.navigator.intercept(self, watch.get.map(_.uri)),
                    aria.label := s"Play ${course.title}",
                    Icons(Icon.Play)
                  )
                }
              },
              div(
                cls := "detail-art-caption",
                span(course.eyebrow),
                strong(course.technologies.mkString(" · "))
              )
            )
          )
        )
      ),
      sectionTag(
        cls := "detail-content app-shell",
        div(
          cls := "detail-content__main",
          outcomes(course, isVideo),
          curriculum(ctx, course, isVideo)
        ),
        sidebar(course, isVideo)
      ),
      related
        .map(_.nonEmpty)
        .changes
        .map(nonEmpty => Option.when(nonEmpty)(relatedSection(ctx, related)))
    ).widen

  private def outcomes(
      course: Course,
      isVideo: Boolean
  ): Resource[IO, HtmlElement[IO]] =
    sectionTag(
      cls := "outcomes-panel",
      p(cls := "eyebrow", if isVideo then "Ideas covered" else "What you will learn"),
      h2(
        if isVideo then "A focused perspective from the community."
        else "Build the understanding behind the code."
      ),
      div(
        cls := "outcomes-grid",
        course.outcomes.toList.map(outcome => p(Icons(Icon.CircleCheck), s" $outcome"))
      )
    ).widen

  private def curriculum(
      ctx: AppContext,
      course: Course,
      isVideo: Boolean
  ): Resource[IO, HtmlElement[IO]] =
    sectionTag(
      cls := "curriculum-section",
      div(
        cls := "curriculum-section__heading",
        div(
          p(cls := "eyebrow", if isVideo then "Community video" else "Course curriculum"),
          h2(
            if isVideo then "One complete talk"
            else s"${course.lessons.size} focused lessons"
          )
        ),
        span(s"${course.duration} total")
      ),
      div(
        cls := "curriculum-list",
        course.lessons.zipWithIndex.toList.map { case (lesson, index) =>
          val complete = ctx.store.completedLessons
            .map(_.contains(AppState.completedKey(course.id, lesson.id)))
            .changes
          val destination = AppRoute.Watch(course.slug, lesson.id)
          detailsTag.withSelf { self =>
            (
              Option.when(index == 0)(
                Resource.eval(IO(self.asInstanceOf[dom.Element].setAttribute("open", "")))
              ),
              summaryTag(
                span(
                  cls <-- complete.map(done =>
                    List("lesson-index") ++ Option.when(done)("is-complete")
                  ),
                  complete.map(done => Option.when(done)(Icons(Icon.Check))),
                  complete.map(done => Option.unless(done)(f"${index + 1}%02d"))
                ),
                span(
                  cls := "curriculum-list__title",
                  strong(lesson.title),
                  Option.unless(isVideo)(small(lesson.description))
                ),
                Option.when(lesson.preview)(span(cls := "preview-tag", "Preview")),
                span(cls := "curriculum-list__duration", lesson.duration),
                Icons(Icon.ChevronDown, className = "curriculum-list__chevron")
              ),
              div(
                cls := "curriculum-list__details",
                p(lesson.description),
                a.withSelf { self =>
                  (
                    href := ctx.navigator.href(destination),
                    ctx.navigator.intercept(self, destination),
                    Icons(Icon.Play),
                    s" Play ${if isVideo then "video" else "lesson"}"
                  )
                }
              )
            )
          }
        }
      )
    ).widen

  private def sidebar(course: Course, isVideo: Boolean): Resource[IO, HtmlElement[IO]] =
    asideTag(
      cls := "course-sidebar",
      sectionTag(
        p(
          cls := "eyebrow eyebrow--small",
          s"This ${if isVideo then "video" else "course"} includes"
        ),
        ul(
          li(
            Icons(Icon.ListVideo),
            if course.lessonCount == 1 then " 1 on-demand video"
            else s" ${course.lessonCount} on-demand lessons"
          ),
          Option.unless(isVideo)(li(Icons(Icon.Code), " Downloadable project code")),
          li(Icons(Icon.CircleCheck), " Progress tracking"),
          li(Icons(Icon.Globe), " Lifetime access"),
          course.source.map { source =>
            li(
              Icons(Icon.ExternalLink),
              " ",
              a(
                href := source.url,
                target := "_blank",
                rel := List("noreferrer"),
                s"Original on ${source.name}"
              )
            )
          }
        )
      ),
      sectionTag(
        p(cls := "eyebrow eyebrow--small", "Technologies"),
        div(
          cls := "technology-tags",
          course.technologies.toList.map(technology => span(technology))
        )
      ),
      sectionTag(
        p(cls := "eyebrow eyebrow--small", "Before you start"),
        ul(cls := "prerequisite-list", course.prerequisites.toList.map(item => li(item)))
      )
    ).widen

  private def relatedSection(
      ctx: AppContext,
      related: Signal[IO, Vector[Course]]
  ): Resource[IO, HtmlElement[IO]] =
    sectionTag(
      cls := "app-section app-section--lavender related-section",
      div(
        cls := "app-shell",
        div(
          cls := "app-section__heading",
          div(p(cls := "eyebrow", "Keep going"), h2("You may also like"))
        ),
        CourseCard.grid(ctx, related, "course-grid course-grid--three")
      )
    ).widen
