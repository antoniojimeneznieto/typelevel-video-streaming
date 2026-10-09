package typelevel.courses.pages

import calico.html.io.{*, given}
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.dom.{HtmlAnchorElement, HtmlElement, Node}
import org.http4s.Uri
import typelevel.courses.AppContext
import typelevel.courses.components.{CatalogSection, CourseCard, Footer, SiteHeader}
import typelevel.courses.data.Catalog
import typelevel.courses.routing.AppRoute
import typelevel.courses.state.RemoteStateStatus
import typelevel.courses.ui.CatalogPresentation.*
import typelevel.courses.ui.{Icon, Icons}

object LandingPage:
  def apply(ctx: AppContext): Resource[IO, HtmlElement[IO]] =
    val featured    = Catalog.featured
    val courseCount = ctx.catalog.signal.map { catalog =>
      if catalog.status == RemoteStateStatus.Ready then catalog.courses.size.toString else "—"
    }
    val lessonCount = ctx.catalog.signal.map { catalog =>
      if catalog.status == RemoteStateStatus.Ready then
        catalog.courses.map(_.lessonCount).sum.toString
      else "—"
    }

    def productLink(anchor: HtmlAnchorElement[IO], destination: String) =
      val target = Uri.unsafeFromString(destination)
      val login  = AppRoute.Login(Some(destination)).uri
      val route  = ctx.store.user.map(_.fold(login)(_ => target))
      (
        href <-- route.map(_.renderString),
        ctx.navigator.intercept(anchor, route.get),
      )

    div(
      cls := "public-page",
      a(cls := "skip-link", href := "#main-content", "Skip to content"),
      SiteHeader.PublicHeader(ctx),
      mainTag(
        idAttr := "main-content",
        sectionTag(
          cls := "landing-hero",
          div(
            cls := "shell landing-hero__grid",
            div(
              cls := "landing-hero__copy",
              p(cls := "eyebrow", "Learn the Typelevel way"),
              h1("Build scalable systems ", span("with confidence.")),
              p(
                cls := "landing-hero__lede",
                "Deep, practical videos on functional programming, effects, streaming, and production architecture.",
              ),
              div(
                cls := "hero-actions",
                a.withSelf { self =>
                  (
                    cls := "button button--primary button--large",
                    linkTo(ctx, self, AppRoute.Register),
                    "Start learning ",
                    Icons(Icon.ArrowRight),
                  )
                },
                a.withSelf { self =>
                  (
                    cls := "button button--outline button--large",
                    productLink(self, "/browse"),
                    Icons(Icon.Play),
                    " Browse the library",
                  )
                },
              ),
              div(
                cls := "hero-proof",
                aria.label := "Platform highlights",
                div(strong(courseCount), span("curated library items")),
                div(
                  strong(lessonCount),
                  span("videos & lessons"),
                ),
                div(strong("4.9"), span("learner rating")),
              ),
            ),
            div(
              cls := "landing-hero__visual",
              aria.label := "Featured video preview",
              a.withSelf { self =>
                (
                  cls := "hero-art-frame",
                  linkTo(ctx, self, AppRoute.Register),
                  aria.label := s"Register to watch ${featured.course.title.value}",
                  img(
                    src := featured.thumbnail.getOrElse("/learning-network.webp"),
                    alt := "",
                  ),
                  div(
                    cls := "hero-art-frame__topline",
                    span(featured.eyebrow),
                    span(featured.course.level.label),
                  ),
                  span(
                    cls := "hero-play-button",
                    aria.hidden := true,
                    Icons(Icon.Play),
                  ),
                  div(
                    cls := "hero-course-label",
                    span(
                      if featured.lessonCount == 1 then s"1 video · ${featured.duration}"
                      else s"${featured.lessonCount} lessons · ${featured.duration}",
                    ),
                    strong(featured.course.title.value),
                  ),
                )
              },
              div(
                cls := "floating-progress-card",
                span(cls := "floating-progress-card__icon", Icons(Icon.CircleCheck)),
                span(small("Lesson progress"), strong("Saved automatically")),
                div(cls := "mini-progress", span(())),
              ),
              div(cls := "hero-dots", aria.hidden := true),
            ),
          ),
        ),
        sectionTag(
          cls := "ecosystem-strip",
          aria.label := "Technologies covered",
          div(
            cls := "shell ecosystem-strip__inner",
            p("Learn across the ecosystem"),
            div(
              span("Cats"),
              span("Cats Effect"),
              span("FS2"),
              span("http4s"),
              span("Circe"),
              span("Skunk"),
            ),
          ),
        ),
        sectionTag(
          cls := "section featured-section",
          idAttr := "courses",
          div(
            cls := "shell",
            div(
              cls := "section-heading",
              div(
                p(cls := "eyebrow", "Curated for momentum"),
                h2("Start with what you want to build."),
              ),
              a.withSelf { self =>
                (
                  cls := "text-link",
                  productLink(self, "/browse"),
                  "Explore the library ",
                  Icons(Icon.ArrowRight),
                )
              },
            ),
            CatalogSection(ctx)(
              div(
                cls := "course-grid course-grid--three",
                children <-- ctx.catalog.courses.map { courses =>
                  courses.take(3).toList.map { course =>
                    CourseCard(ctx, course, destination = Some(AppRoute.Register)).widen[Node[IO]]
                  }
                },
              ).widen,
            ),
          ),
        ),
        sectionTag(
          cls := "section paths-section",
          idAttr := "paths",
          div(
            cls := "shell",
            div(
              cls := "section-heading section-heading--light",
              div(
                p(cls := "eyebrow", "Guided learning paths"),
                h2("A clear route through the ecosystem."),
              ),
              p(
                "Follow a thoughtful sequence, keep your progress, and know exactly what comes next.",
              ),
            ),
            CatalogSection(ctx, label = "learning paths")(
              div(
                cls := "path-grid",
                children <-- ctx.catalog.learningPaths.map { learningPaths =>
                  learningPaths.zipWithIndex.toList.map { (path, index) =>
                    val destination = s"/paths#${path.id.value}"
                    a.withSelf { self =>
                      (
                        cls := s"path-card path-card--${path.tone.cssName}",
                        productLink(self, destination),
                        div(cls := "path-card__number", f"${index + 1}%02d"),
                        div(cls := "path-card__icon", pathIcon(index)),
                        span(cls := "eyebrow eyebrow--small", s"${path.level.label} path"),
                        h3(path.title.value),
                        p(path.description.value),
                        div(
                          cls := "path-card__meta",
                          span(s"${path.courseIds.size} courses"),
                          span(path.timeLabel.value),
                          Icons(Icon.ArrowRight),
                        ),
                      )
                    }.widen[Node[IO]]
                  }
                },
              ).widen,
            ),
          ),
        ),
        sectionTag(
          cls := "section learning-values",
          idAttr := "why-typelevel",
          div(
            cls := "shell learning-values__grid",
            div(
              cls := "learning-values__intro",
              p(cls := "eyebrow", "Made for real understanding"),
              h2("Learn from the Typelevel community."),
              p(
                "A growing collection of talks and videos about the ideas, tools, and decisions behind functional Scala.",
              ),
              a.withSelf { self =>
                (
                  cls := "button button--outline",
                  productLink(self, "/browse"),
                  "Explore the library ",
                  Icons(Icon.ArrowRight),
                )
              },
            ),
            div(
              cls := "value-list",
              valueItem(
                Icon.BookOpen,
                "Knowledge from the community",
                "Watch talks from people building and using the Typelevel ecosystem.",
              ),
              valueItem(
                Icon.Code,
                "Decisions behind the code",
                "Understand the tradeoffs and experience that shape real systems.",
              ),
              valueItem(
                Icon.Sparkles,
                "Keep your place",
                "Save videos and resume from where you stopped.",
              ),
            ),
          ),
        ),
        sectionTag(
          cls := "testimonial-section",
          div(
            cls := "shell testimonial-section__inner",
            span(cls := "testimonial-section__quote", aria.hidden := true, "“"),
            blockQuote(
              "Typelevel is more than a collection of libraries. It is a community sharing how functional programming works in practice.",
            ),
            div(
              cls := "testimonial-author",
              span("TL"),
              p(
                strong("Typelevel Learning Center"),
                small("Talks and ideas from across the ecosystem"),
              ),
            ),
          ),
        ),
        sectionTag(
          cls := "section final-cta",
          div(
            cls := "shell final-cta__panel",
            div(cls := "final-cta__shape", aria.hidden := true),
            p(cls := "eyebrow", "Start exploring"),
            h2("Find a talk that interests you."),
            p(
              "Create an account to watch community videos, save your favorites, and continue where you left off.",
            ),
            div(
              cls := "hero-actions",
              a.withSelf { self =>
                (
                  cls := "button button--light button--large",
                  linkTo(ctx, self, AppRoute.Register),
                  "Create a free account ",
                  Icons(Icon.ArrowRight),
                )
              },
              a.withSelf { self =>
                (
                  cls := "button button--ghost-light button--large",
                  productLink(self, "/browse"),
                  "Explore the library",
                )
              },
            ),
          ),
        ),
      ),
      Footer(ctx),
    )

  private def linkTo(
      ctx: AppContext,
      anchor: HtmlAnchorElement[IO],
      route: AppRoute,
  ) =
    (
      href := ctx.navigator.href(route),
      ctx.navigator.intercept(anchor, route),
    )

  private def pathIcon(index: Int) = Icons(index match
    case 0 => Icon.Code
    case 1 => Icon.Infinity
    case _ => Icon.Layers)

  private def valueItem(icon: Icon, title: String, description: String) =
    articleTag(
      span(Icons(icon)),
      div(h3(title), p(description)),
    )
