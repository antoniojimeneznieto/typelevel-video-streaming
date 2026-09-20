package org.typelevel.video.streaming.backend.runtime.http

import org.http4s.otel4s.middleware.client.UriTemplateClassifier
import org.http4s.otel4s.middleware.server.RouteClassifier
import org.http4s.{RequestPrelude, Uri}
import smithy.api.Http
import smithy4s.http.{HttpEndpoint, HttpMethod}
import smithy4s.http4s.kernel.toSmithy4sHttpUri
import smithy4s.Service

object SmithyRouteClassifier:

  final private case class Entry(
      endpoint: HttpEndpoint[?],
      routeTemplate: String,
  )

  def apply[Alg[_[_, _, _, _, _]]](
      service: Service[Alg],
  ): RouteClassifier =
    classifier(service, matchMountPath = "", routePrefix = "")

  /** Classifies a Smithy service mounted below a public gateway path.
    *
    * The emitted route retains the mount point, while matching is performed against the service's
    * Smithy HTTP templates.
    */
  def mounted[Alg[_[_, _, _, _, _]]](
      mountPath: String,
      service: Service[Alg],
  ): RouteClassifier =
    val normalizedPath = normalizedMountPath(mountPath)
    classifier(service, matchMountPath = normalizedPath, routePrefix = normalizedPath)

  /** Matches a Smithy service below a base path without including that path in the route value. */
  def belowBasePath[Alg[_[_, _, _, _, _]]](
      basePath: String,
      service: Service[Alg],
  ): RouteClassifier =
    classifier(service, matchMountPath = normalizedMountPath(basePath), routePrefix = "")

  /** Derives a client URL-template classifier from a Smithy service. */
  def urlTemplatesBelowBasePath[Alg[_[_, _, _, _, _]]](
      basePath: String,
      service: Service[Alg],
  ): UriTemplateClassifier =
    val entries = service.endpoints.toList.flatMap { endpoint =>
      for
        http         <- endpoint.hints.get(using Http)
        httpEndpoint <- HttpEndpoint.cast(endpoint.schema).toOption
      yield Entry(httpEndpoint, http.uri.value.takeWhile(_ != '?'))
    }
    val matchMountPath = normalizedMountPath(basePath)

    new UriTemplateClassifier:
      override def classify(uri: Uri): Option[String] =
        val smithyUri = toSmithy4sHttpUri(stripMountPath(uri, matchMountPath))

        entries
          .find { entry =>
            entry.endpoint.matches(smithyUri.path).isDefined &&
            staticQueriesMatch(
              actual   = smithyUri.queryParamsAsMap,
              required = entry.endpoint.staticQueryParams,
            )
          }
          .map(_.routeTemplate)

  def firstMatch(classifiers: RouteClassifier*): RouteClassifier =
    request =>
      classifiers.iterator.map(_.classify(request)).collectFirst { case Some(route) => route }

  private def classifier[Alg[_[_, _, _, _, _]]](
      service: Service[Alg],
      matchMountPath: String,
      routePrefix: String,
  ): RouteClassifier =
    val byMethod: Map[HttpMethod, List[Entry]] =
      service.endpoints.toList
        .flatMap { endpoint =>
          for
            http         <- endpoint.hints.get(using Http)
            httpEndpoint <- HttpEndpoint.cast(endpoint.schema).toOption
          yield
            // Static query literals participate in matching but should not
            // become part of the OpenTelemetry http.route value.
            val routeTemplate =
              routePrefix + http.uri.value.takeWhile(_ != '?')

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
          toSmithy4sHttpUri(stripMountPath(request.uri, matchMountPath))

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

  private def normalizedMountPath(mountPath: String): String =
    val normalized = mountPath.stripSuffix("/")
    require(
      normalized.isEmpty || normalized.startsWith("/"),
      "mount path must start with '/', or be '/'.",
    )
    normalized

  private def stripMountPath(uri: Uri, mountPath: String): Uri =
    if mountPath.isEmpty then uri
    else
      val renderedPath  = uri.path.renderString
      val remainingPath =
        if renderedPath == mountPath then "/"
        else if renderedPath.startsWith(s"$mountPath/") then renderedPath.stripPrefix(mountPath)
        else "//unmatched"

      uri.withPath(Uri.Path.unsafeFromString(remainingPath))
