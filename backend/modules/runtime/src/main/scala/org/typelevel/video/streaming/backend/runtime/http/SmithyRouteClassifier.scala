package org.typelevel.video.streaming.backend.runtime.http

import org.http4s.RequestPrelude
import org.http4s.otel4s.middleware.server.RouteClassifier
import smithy.api.Http
import smithy4s.Service
import smithy4s.http.{HttpEndpoint, HttpMethod}
import smithy4s.http4s.kernel.toSmithy4sHttpUri

object SmithyRouteClassifier:

  final private case class Entry(
      endpoint: HttpEndpoint[?],
      routeTemplate: String,
  )

  def apply[Alg[_[_, _, _, _, _]]](
      service: Service[Alg],
  ): RouteClassifier =
    val byMethod: Map[HttpMethod, List[Entry]] =
      service.endpoints.toList
        .flatMap { endpoint =>
          for
            http         <- endpoint.hints.get(Http)
            httpEndpoint <- HttpEndpoint.cast(endpoint.schema).toOption
          yield
            // Static query literals participate in matching but should not
            // become part of the OpenTelemetry http.route value.
            val routeTemplate =
              http.uri.value.takeWhile(_ != '?')

            Entry(httpEndpoint, routeTemplate)
        }
        .groupBy(_.endpoint.method)
        .view
        .mapValues(
          _.sortWith { (left, right) =>
            HttpEndpoint.moreSpecific(
              left.endpoint,
              right.endpoint,
            )
          },
        )
        .toMap

    new RouteClassifier:
      override def classify(
          request: RequestPrelude,
      ): Option[String] =
        val method =
          HttpMethod.fromStringOrDefault(request.method.name)

        val uri =
          toSmithy4sHttpUri(request.uri)

        byMethod
          .get(method)
          .flatMap(
            _.find { entry =>
              entry.endpoint.matches(uri.path).isDefined &&
              staticQueriesMatch(
                actual   = uri.queryParamsAsMap,
                required = entry.endpoint.staticQueryParams,
              )
            }.map(_.routeTemplate),
          )

  private def staticQueriesMatch(
      actual: Map[String, Seq[Option[String]]],
      required: Map[String, Seq[Option[String]]],
  ): Boolean =
    required.forall { case (key, requiredValues) =>
      actual.get(key).exists { actualValues =>
        // Matches Smithy4s behavior: ?foo and ?foo= are equivalent.
        val normalizedActual =
          actualValues.toSet.flatMap {
            case None | Some("") => Set(None, Some(""))
            case value => Set(value)
          }

        requiredValues.forall(normalizedActual.contains)
      }
    }
