package typelevel.courses.pages

import calico.html.io.{*, given}
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.dom.HtmlElement
import typelevel.courses.AppContext
import typelevel.courses.components.{Artwork, CatalogSection, SiteHeader}
import typelevel.courses.routing.AppRoute
import typelevel.courses.ui.CatalogPresentation.*
import typelevel.courses.ui.{Icon, Icons}

object PathsPage:
  def apply(ctx: AppContext): Resource[IO, HtmlElement[IO]] =
    div(
      cls := "app-page paths-page",
      SiteHeader.AppHeader(ctx),
      mainTag(
        headerTag(
          cls := "paths-hero",
          div(
            cls := "app-shell paths-hero__inner",
            span(cls := "paths-hero__icon", Icons(Icon.Route)),
            p(cls := "eyebrow", "Guided learning paths"),
            h1("Know what to learn next."),
            p("Curated sequences that connect foundational ideas to production decisions."),
          ),
        ),
        CatalogSection(ctx, "learning paths")(paths(ctx)),
      ),
    ).widen

  private def paths(ctx: AppContext): Resource[IO, HtmlElement[IO]] =
    div(
      styleAttr := "display: contents",
      ctx.catalog.signal.map { catalog =>
        val coursesById = catalog.courses.map(course => course.course.id -> course).toMap

        div(
          cls := "app-shell paths-list",
          catalog.learningPaths.zipWithIndex.toList.map { case (learningPath, pathIndex) =>
            val pathCourses = learningPath.courseIds.flatMap(coursesById.get)
            sectionTag(
              cls := s"path-detail path-detail--${learningPath.tone.cssName}",
              idAttr := learningPath.id.value,
              div(
                cls := "path-detail__intro",
                span(cls := "path-detail__number", f"${pathIndex + 1}%02d"),
                p(
                  cls := "eyebrow",
                  s"${learningPath.level.label} · ${learningPath.timeLabel.value}",
                ),
                h2(learningPath.title.value),
                p(learningPath.description.value),
                ul(
                  li(Icons(Icon.CircleCheck), " Curated course order"),
                  li(Icons(Icon.Clock), " Learn at your own pace"),
                ),
                pathCourses.headOption.map { firstCourse =>
                  val destination = AppRoute.Course(firstCourse.course.slug.value)
                  a.withSelf { self =>
                    (
                      cls := "button button--primary",
                      href := ctx.navigator.href(destination),
                      ctx.navigator.intercept(self, destination),
                      "Start this path ",
                      Icons(Icon.ArrowRight),
                    )
                  }
                },
              ),
              div(
                cls := "path-detail__courses",
                pathCourses.zipWithIndex.map { case (course, index) =>
                  val destination = AppRoute.Course(course.course.slug.value)
                  val lessonLabel =
                    if course.lessonCount == 1 then "1 video"
                    else s"${course.lessonCount} lessons"
                  a.withSelf { self =>
                    (
                      href := ctx.navigator.href(destination),
                      ctx.navigator.intercept(self, destination),
                      span(cls := "path-detail__step", f"${index + 1}%02d"),
                      Artwork(course.artwork, course.artLabel, thumbnail = course.thumbnail),
                      span(
                        small(course.course.topic.value),
                        strong(course.course.title.value),
                        em(s"${course.duration} · $lessonLabel"),
                      ),
                      Icons(Icon.ArrowRight),
                    )
                  }
                },
              ),
            )
          },
        )
      },
    ).widen
