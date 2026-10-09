package typelevel.courses.pages

import calico.frp.given
import calico.html.io.{*, given}
import cats.effect.{IO, Resource}
import cats.effect.std.Supervisor
import cats.kernel.Eq
import cats.syntax.all.*
import fs2.concurrent.{Signal, SignallingRef}
import fs2.dom.HtmlElement
import fs2.Stream
import org.http4s.{Query, Uri}
import org.typelevel.video.streaming.backend.catalog.api.ListCoursesInput
import org.typelevel.video.streaming.backend.catalog.domain.{
  CourseKind,
  CourseLevel,
  PageLimit,
  SearchQuery,
  Topic,
}
import typelevel.courses.AppContext
import typelevel.courses.components.{CourseCard, SiteHeader}
import typelevel.courses.routing.AppRoute
import typelevel.courses.state.RemoteStateStatus
import typelevel.courses.ui.CatalogPresentation.*
import typelevel.courses.ui.{CourseView, FormEvents, Icon, Icons}

object SearchPage:
  final private case class SearchParams(
      query: String,
      topic: String,
      level: String,
      format: String,
  ):
    def hasFilters: Boolean =
      query.nonEmpty || topic != "All topics" || level != "All levels" || format != "All formats"

  final private case class Draft(source: String, value: String)

  final private case class SearchResult(
      params: SearchParams,
      items: Vector[CourseView] = Vector.empty,
      status: RemoteStateStatus = RemoteStateStatus.Loading,
  )

  private val levels  = "All levels" +: CourseLevel.values.map(_.label).toVector
  private val formats = "All formats" +: (CourseKind.values.map(_.label).toVector :+ "Video")

  def apply(ctx: AppContext): Resource[IO, HtmlElement[IO]] = for
    supervisor  <- Supervisor[IO](await = false)
    initialUri  <- Resource.eval(ctx.navigator.location.get)
    initialQuery = params(initialUri).query
    draft       <- SignallingRef[IO].of(Draft(initialQuery, initialQuery)).toResource
    result      <- SignallingRef[IO].of(SearchResult(params(initialUri))).toResource
    retry       <- SignallingRef[IO].of(0).toResource
    searchParams = ctx.navigator.location.map(params).changes(using Eq.fromUniversalEquals)
    _           <- searchRequests(ctx, searchParams, retry, result).background
    retrySearch  = supervisor.supervise {
                    retry.update(_ + 1) *> ctx.catalog.signal.get.flatMap { catalog =>
                      IO.whenA(catalog.status == RemoteStateStatus.Error)(ctx.catalog.refresh)
                    }
                  }.void
    page <- render(ctx, draft, searchParams, result, retrySearch)
  yield page

  private def searchRequests(
      ctx: AppContext,
      current: Signal[IO, SearchParams],
      retry: Signal[IO, Int],
      result: SignallingRef[IO, SearchResult],
  ): IO[Unit] =
    (current, retry).tupled.discrete
      .switchMap { case (params, _) =>
        val fetch = IO
          .fromEither(filters(params).leftMap(new IllegalArgumentException(_)))
          .flatMap(ctx.catalog.queryCourses)
          .map(
            _.filter(view => params.format == "All formats" || view.formatLabel == params.format),
          )
        Stream.eval(
          result.set(SearchResult(params)) *> fetch.attempt.flatMap {
            case Right(items) => result.set(SearchResult(params, items, RemoteStateStatus.Ready))
            case Left(_) => result.set(SearchResult(params, status = RemoteStateStatus.Error))
          },
        )
      }
      .compile
      .drain

  private def filters(params: SearchParams): Either[String, ListCoursesInput] =
    (
      Option.when(params.query.trim.nonEmpty)(params.query.trim).traverse(SearchQuery(_)),
      Option.unless(params.topic == "All topics")(params.topic).traverse(Topic(_)),
    ).mapN { (query, topic) =>
      ListCoursesInput(
        query = query,
        topic = topic,
        level = CourseLevel.values.find(_.label == params.level),
        kind  = CourseKind.values.find(_.label == params.format),
        limit = PageLimit.unsafeApply(100),
      )
    }

  private def render(
      ctx: AppContext,
      draft: SignallingRef[IO, Draft],
      searchParams: Signal[IO, SearchParams],
      result: Signal[IO, SearchResult],
      retry: IO[Unit],
  ): Resource[IO, HtmlElement[IO]] =
    val draftValue = (searchParams, draft).mapN { (current, value) =>
      if value.source == current.query then value.value else current.query
    }

    def setQuery(uri: Uri, query: String): IO[Unit] =
      draft.set(Draft(query, query)) *>
        (if params(uri).query == query then retry
         else ctx.navigator.go(updateParam(uri, "q", query, "")))

    val submit = (ctx.navigator.location.get, draftValue.get).flatMapN { (uri, value) =>
      setQuery(uri, value.trim)
    }
    val clearQuery = ctx.navigator.location.get.flatMap(uri => setQuery(uri, ""))
    val clearAll   = draft.set(Draft("", "")) *> ctx.navigator.go(AppRoute.Search)

    div(
      cls := "app-page search-page",
      SiteHeader.AppHeader(ctx),
      mainTag(
        cls := "app-shell search-main",
        div(
          cls := "search-page__heading",
          p(cls := "eyebrow", "Course library"),
          h1("Find your next useful idea."),
          p("Search by concept, project, technology, or instructor."),
        ),
        form(
          cls := "library-search",
          onSubmit(submit),
          Icons(Icon.Search),
          input.withSelf { self =>
            (
              value <-- draftValue,
              onInput(_ =>
                (searchParams.get, self.value.get).flatMapN { (current, value) =>
                  draft.set(Draft(current.query, value))
                },
              ),
              placeholder := "Try “structured concurrency” or “http4s”…",
              maxLength := 100,
              aria.label := "Search course library",
              autoFocus := true,
            )
          },
          draftValue.map(_.nonEmpty).changes.map { nonEmpty =>
            Option.when(nonEmpty) {
              button(
                typ := "button",
                cls := "library-search__clear",
                aria.label := "Clear search",
                onClick(clearQuery),
                Icons(Icon.X),
              )
            }
          },
          button(typ := "submit", cls := "button button--primary", "Search"),
        ).flatTap(FormEvents.preventNativeSubmit),
        filters(ctx, searchParams, clearAll),
        results(ctx, searchParams, result, clearAll, retry),
      ),
    ).widen

  private def filters(
      ctx: AppContext,
      current: Signal[IO, SearchParams],
      clearAll: IO[Unit],
  ): Resource[IO, HtmlElement[IO]] =
    def filterSelect(
        labelText: String,
        key: String,
        defaultValue: String,
        values: Signal[IO, Vector[String]],
        selected: SearchParams => String,
    ) = calico.html.io.label(
      span(labelText),
      select.withSelf { self =>
        (
          children[String](item => option(value := item, item)) <-- values.map(_.toList),
          value <-- (values, current).mapN((_, params) => selected(params)),
          onChange(_ =>
            (ctx.navigator.location.get, self.value.get).flatMapN { (uri, nextValue) =>
              ctx.navigator.go(updateParam(uri, key, nextValue, defaultValue))
            },
          ),
        )
      },
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
            "Clear all",
          )
        }
      },
    ).widen

  private def results(
      ctx: AppContext,
      current: Signal[IO, SearchParams],
      result: Signal[IO, SearchResult],
      clearAll: IO[Unit],
      retry: IO[Unit],
  ): Resource[IO, HtmlElement[IO]] =
    val currentResult = (current, result).mapN { (params, result) =>
      if result.params == params then result else SearchResult(params)
    }
    val matching = currentResult.map(_.items).changes(using Eq.fromUniversalEquals)

    div(
      div(
        cls := "search-results-heading",
        div(
          h2(current.map { params =>
            if params.query.nonEmpty then s"Results for “${params.query}”"
            else "Explore everything"
          }),
          span(
            aria.live := "polite",
            currentResult.map { result =>
              result.status match
                case RemoteStateStatus.Loading | RemoteStateStatus.Idle => "Searching…"
                case RemoteStateStatus.Error => "Catalog unavailable"
                case RemoteStateStatus.Ready =>
                  s"${result.items.size} ${if result.items.size == 1 then "result" else "results"}"
            },
          ),
        ),
      ),
      currentResult
        .map(result => result.status -> result.items.nonEmpty)
        .changes(using Eq.fromUniversalEquals)
        .map {
          case (RemoteStateStatus.Loading | RemoteStateStatus.Idle, _) =>
            div(
              cls := "catalog-state",
              role := List("status"),
              aria.busy := true,
              span(cls := "session-check__spinner", aria.hidden := true),
              "Searching the catalog…",
            ).widen
          case (RemoteStateStatus.Error, _) =>
            div(
              cls := "catalog-state",
              role := List("alert"),
              h3("We could not search the catalog."),
              p("Please try again."),
              button(typ := "button", cls := "button button--primary", onClick(retry), "Try again"),
            ).widen
          case (RemoteStateStatus.Ready, true) =>
            CourseCard.grid(ctx, matching, "course-grid course-grid--three search-results-grid")
          case (RemoteStateStatus.Ready, false) =>
            div(
              cls := "empty-state",
              span(Icons(Icon.Search)),
              h2("No exact match—yet."),
              p(
                "Try a broader topic or clear a filter. “effects”, “Scala”, and “testing” are good places to start.",
              ),
              button(
                cls := "button button--primary",
                typ := "button",
                onClick(clearAll),
                "Explore all courses",
              ),
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
                " Follow the Functional Scala Foundations path for a guided start.",
              ),
              a.withSelf { self =>
                (
                  href := ctx.navigator.href(destination),
                  ctx.navigator.intercept(self, destination),
                  "View the path",
                )
              },
            )
          }
        },
    ).widen

  private def params(uri: Uri): SearchParams = SearchParams(
    query  = uri.query.params.getOrElse("q", ""),
    topic  = uri.query.params.getOrElse("topic", "All topics"),
    level  = uri.query.params.getOrElse("level", "All levels"),
    format = uri.query.params.getOrElse("format", "All formats"),
  )

  private def updateParam(uri: Uri, key: String, value: String, defaultValue: String): Uri =
    val updated =
      if value == defaultValue then uri.query.params - key
      else uri.query.params.updated(key, value)
    AppRoute.Search.uri.copy(query = Query.fromPairs(updated.toSeq*))
