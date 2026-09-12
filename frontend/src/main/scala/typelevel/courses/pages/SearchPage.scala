package typelevel.courses.pages

import calico.frp.given
import calico.html.io.{*, given}
import cats.effect.{IO, Resource}
import cats.kernel.Eq
import cats.syntax.all.*
import fs2.concurrent.{Signal, SignallingRef}
import fs2.dom.HtmlElement
import org.http4s.{Query, Uri}
import typelevel.courses.AppContext
import typelevel.courses.components.{CourseCard, SiteHeader}
import typelevel.courses.domain.Course
import typelevel.courses.routing.AppRoute
import typelevel.courses.ui.{Icon, Icons}

object SearchPage:
  final private case class SearchParams(
      query: String,
      topic: String,
      level: String,
      format: String
  ):
    def hasFilters: Boolean =
      query.nonEmpty || topic != "All topics" || level != "All levels" || format != "All formats"

  final private case class Draft(source: String, value: String)

  private val levels  = Vector("All levels", "Beginner", "Intermediate", "Advanced")
  private val formats = Vector("All formats", "Course", "Workshop", "Talk", "Video")

  def apply(ctx: AppContext): Resource[IO, HtmlElement[IO]] = for
    initialUri  <- Resource.eval(ctx.navigator.location.get)
    initialQuery = params(initialUri).query
    draft       <- SignallingRef[IO].of(Draft(initialQuery, initialQuery)).toResource
    result      <- render(ctx, draft)
  yield result

  private def render(
      ctx: AppContext,
      draft: SignallingRef[IO, Draft]
  ): Resource[IO, HtmlElement[IO]] =
    val searchParams = ctx.navigator.location.map(params).changes(using Eq.fromUniversalEquals)
    val draftValue   = (searchParams, draft).mapN { (current, value) =>
      if value.source == current.query then value.value else current.query
    }

    val submit = for
      uri        <- ctx.navigator.location.get
      value      <- draftValue.get
      trimmed     = value.trim
      destination = updateParam(uri, "q", trimmed, "")
      _          <- draft.set(Draft(trimmed, trimmed))
      _          <- ctx.navigator.go(destination)
    yield ()

    val clearAll = draft.set(Draft("", "")) *> ctx.navigator.go(AppRoute.Search)

    div(
      cls := "app-page search-page",
      SiteHeader.AppHeader(ctx),
      mainTag(
        cls := "app-shell search-main",
        div(
          cls := "search-page__heading",
          p(cls := "eyebrow", "Course library"),
          h1("Find your next useful idea."),
          p("Search by concept, project, technology, or instructor.")
        ),
        form(
          cls := "library-search",
          onSubmit(event => event.preventDefault *> submit),
          Icons(Icon.Search),
          input.withSelf { self =>
            (
              value <-- draftValue,
              onInput --> (_.foreach(_ =>
                (searchParams.get, self.value.get).flatMapN { (current, value) =>
                  draft.set(Draft(current.query, value))
                }
              )),
              placeholder := "Try “structured concurrency” or “http4s”…",
              aria.label := "Search course library",
              autoFocus := true
            )
          },
          draftValue.map(_.nonEmpty).changes.map { nonEmpty =>
            Option.when(nonEmpty) {
              button(
                typ := "button",
                cls := "library-search__clear",
                aria.label := "Clear search",
                onClick {
                  for
                    uri <- ctx.navigator.location.get
                    _   <- draft.set(Draft("", ""))
                    _   <- ctx.navigator.go(updateParam(uri, "q", "", ""))
                  yield ()
                },
                Icons(Icon.X)
              )
            }
          },
          button(typ := "submit", cls := "button button--primary", "Search")
        ),
        filters(ctx, searchParams, clearAll),
        results(ctx, searchParams, ctx.catalog.courses, clearAll)
      )
    ).widen

  private def filters(
      ctx: AppContext,
      current: Signal[IO, SearchParams],
      clearAll: IO[Unit]
  ): Resource[IO, HtmlElement[IO]] =
    def filterSelect(
        labelText: String,
        key: String,
        defaultValue: String,
        values: Signal[IO, Vector[String]],
        selected: SearchParams => String
    ) = label(
      span(labelText),
      select.withSelf { self =>
        (
          children[String](item => option(value := item, item)) <-- values.map(_.toList),
          value <-- (values, current).mapN((_, params) => selected(params)),
          onChange --> (_.foreach(_ =>
            (ctx.navigator.location.get, self.value.get).flatMapN { (uri, nextValue) =>
              ctx.navigator.go(updateParam(uri, key, nextValue, defaultValue))
            }
          ))
        )
      }
    )

    div(
      cls := "search-filters",
      span(cls := "search-filters__label", Icons(Icon.SlidersHorizontal), " Filters"),
      filterSelect("Topic", "topic", "All topics", ctx.catalog.topics, _.topic),
      filterSelect("Level", "level", "All levels", Signal.constant(levels), _.level),
      filterSelect("Format", "format", "All formats", Signal.constant(formats), _.format),
      current.map(_.hasFilters).changes.map { hasFilters =>
        Option.when(hasFilters) {
          button(
            typ := "button",
            cls := "search-filters__reset",
            onClick(clearAll),
            "Clear all"
          )
        }
      }
    ).widen

  private def results(
      ctx: AppContext,
      current: Signal[IO, SearchParams],
      courses: Signal[IO, Vector[Course]],
      clearAll: IO[Unit]
  ): Resource[IO, HtmlElement[IO]] =
    val matching = (current, courses).mapN(matchingCourses).changes(using Eq.fromUniversalEquals)

    div(
      div(
        cls := "search-results-heading",
        div(
          h2(current.map { params =>
            if params.query.nonEmpty then s"Results for “${params.query}”"
            else "Explore everything"
          }),
          span(
            matching.map(items =>
              s"${items.size} ${if items.size == 1 then "result" else "results"}"
            )
          )
        )
      ),
      matching.map(_.nonEmpty).changes.map {
        case true =>
          CourseCard.grid(ctx, matching, "course-grid course-grid--three search-results-grid")
        case false =>
          div(
            cls := "empty-state",
            span(Icons(Icon.Search)),
            h2("No exact match—yet."),
            p(
              "Try a broader topic or clear a filter. “effects”, “Scala”, and “testing” are good places to start."
            ),
            button(
              cls := "button button--primary",
              typ := "button",
              onClick(clearAll),
              "Explore all courses"
            )
          ).widen
      },
      (current, matching)
        .mapN((params, items) => params.query.isEmpty && items.nonEmpty)
        .changes
        .map { showSuggestion =>
          Option.when(showSuggestion) {
            val destination = AppRoute.Paths.uri.withFragment("foundations")
            sectionTag(
              cls := "search-suggestion",
              Icons(Icon.Sparkles),
              p(
                strong("Not sure where to begin?"),
                " Follow the Functional Scala Foundations path for a guided start."
              ),
              a.withSelf { self =>
                (
                  href := ctx.navigator.href(destination),
                  ctx.navigator.intercept(self, destination),
                  "View the path"
                )
              }
            )
          }
        }
    ).widen

  private def matchingCourses(current: SearchParams, courses: Vector[Course]): Vector[Course] =
    val normalized = current.query.trim.toLowerCase
    courses.filter { course =>
      val haystack = (Vector(
        course.title,
        course.shortDescription,
        course.description,
        course.topic,
        course.instructor.name
      ) ++ course.technologies).mkString(" ").toLowerCase
      val matchesQuery  = normalized.isEmpty || haystack.contains(normalized)
      val matchesTopic  = current.topic == "All topics" || course.topic == current.topic
      val matchesLevel  = current.level == "All levels" || course.level.label == current.level
      val matchesFormat = current.format == "All formats" || course.format.label == current.format
      matchesQuery && matchesTopic && matchesLevel && matchesFormat
    }

  private def params(uri: Uri): SearchParams = SearchParams(
    query  = uri.query.params.getOrElse("q", ""),
    topic  = uri.query.params.getOrElse("topic", "All topics"),
    level  = uri.query.params.getOrElse("level", "All levels"),
    format = uri.query.params.getOrElse("format", "All formats")
  )

  private def updateParam(uri: Uri, key: String, value: String, defaultValue: String): Uri =
    val updated =
      if value == defaultValue then uri.query.params - key
      else uri.query.params.updated(key, value)
    AppRoute.Search.uri.copy(query = Query.fromPairs(updated.toSeq*))
