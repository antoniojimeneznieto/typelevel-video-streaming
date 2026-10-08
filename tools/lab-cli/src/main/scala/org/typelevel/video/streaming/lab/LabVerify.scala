package org.typelevel.video.streaming.lab

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.{Decoder, DecodingFailure, Json}
import io.circe.parser.parse
import org.http4s.{Request, Uri}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import scala.concurrent.duration.*

private[lab] object LabVerify:
  private val container                            = "typelevel-video-streaming-lab-traffic"
  private def httpClient: Resource[IO, Client[IO]] =
    EmberClientBuilder.default[IO].withTimeout(10.seconds).build

  private val metrics = Map(
    "offered" -> """sum(rate(lab_traffic_arrivals_total{profile="catalog-courses"}[30s]))""",
    "sent" -> """sum(rate(lab_traffic_arrivals_total{profile="catalog-courses",result="sent"}[30s]))""",
    "gateway_rate" -> """sum(rate(http_server_request_duration_seconds_count{service_name="gateway-service",classifier="/api/catalog/courses",http_phase="body"}[30s]))""",
    "client_rate" -> """sum(rate(http_client_request_duration_seconds_count{service_name="gateway-service",classifier="catalog:/courses",http_phase="body"}[30s]))""",
    "catalog_rate" -> """sum(rate(http_server_request_duration_seconds_count{service_name="catalog-service",classifier="/courses",http_phase="body"}[30s]))""",
    "gateway_p95" -> """histogram_quantile(0.95,sum by (le) (rate(http_server_request_duration_seconds_bucket{service_name="gateway-service",classifier="/api/catalog/courses",http_phase="body"}[30s])))""",
    "client_p95" -> """histogram_quantile(0.95,sum by (le) (rate(http_client_request_duration_seconds_bucket{service_name="gateway-service",classifier="catalog:/courses",http_phase="body"}[30s])))""",
    "catalog_p95" -> """histogram_quantile(0.95,sum by (le) (rate(http_server_request_duration_seconds_bucket{service_name="catalog-service",classifier="/courses",http_phase="body"}[30s])))""",
  )

  private def requireThat(condition: Boolean, message: String): IO[Unit] =
    IO.raiseUnless(condition)(new IllegalStateException(message))

  private def getJson(client: Client[IO], url: String): IO[Json] =
    for
      uri  <- IO.fromEither(Uri.fromString(url))
      json <-
        client
          .run(Request[IO](uri = uri))
          .use { response =>
            for
              _ <- requireThat(response.status.code == 200, s"HTTP ${response.status.code}: $url")
              body  <- response.as[String]
              value <- IO.fromEither(parse(body))
            yield value
          }
          .timeout(10.seconds)
    yield json

  private def query(base: String, path: String, parameters: (String, String)*): String =
    val suffix = parameters
      .map { (key, value) =>
        s"${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
      }
      .mkString("&")
    base.stripSuffix("/") + path + (if suffix.isEmpty then "" else s"?$suffix")

  private case class MetricSample(value: (Double, String))
  private given Decoder[MetricSample] = Decoder.forProduct1("value")(MetricSample.apply)

  private[lab] def decodeMetric(json: Json): Decoder.Result[Double] =
    for
      samples <- json.hcursor.downField("data").get[Vector[MetricSample]]("result")
      value   <- samples match
                 case Vector(sample) =>
                   sample.value._2.toDoubleOption
                     .filter(_.isFinite)
                     .toRight(
                       DecodingFailure("Expected a finite metric value", json.hcursor.history),
                     )
                 case _ =>
                   Left(DecodingFailure("Expected exactly one metric series", json.hcursor.history))
    yield value

  private def metric(client: Client[IO], grafana: String, expression: String): IO[Double] =
    for
      json <- getJson(
                client,
                query(
                  grafana,
                  "/api/datasources/proxy/uid/prometheus/api/v1/query",
                  "query" -> expression,
                ),
              )
      value <- IO.fromEither(decodeMetric(json)).adaptError { case error =>
                 new IllegalStateException(s"Invalid metric response for $expression", error)
               }
    yield value

  private def latestReport(root: Path): IO[Json] =
    for
      output <- LabIo.output(root, Seq("docker", "logs", "--tail", "20", container))
      report <- IO.fromOption(
                  output.linesIterator.toVector.reverse
                    .flatMap(line => parse(line).toOption)
                    .find(_.hcursor.get[String]("type").contains("progress")),
                )(new IllegalStateException("No traffic progress report found"))
    yield report

  private[lab] def checkWindow(
      name: String,
      rate: Int,
      values: Map[String, Double],
      report: Json,
  ): IO[Unit] =
    for
      valid  <- IO.fromEither(report.hcursor.get[Boolean]("load_valid"))
      failed <- IO.fromEither(report.hcursor.get[Long]("failed"))
      _      <- requireThat(valid && failed == 0, s"$name: generator lost or failed requests")
      _ <- List("offered", "sent", "gateway_rate", "client_rate", "catalog_rate").traverse_ { key =>
             for
               value <- IO.fromOption(values.get(key))(new IllegalStateException(s"Missing $key"))
               _     <- requireThat(
                      value >= rate * 0.8 && value <= rate * 1.2,
                      s"$name: $key rate diverged ($value)",
                    )
             yield ()
           }
    yield ()

  private def observe(
      root: Path,
      client: Client[IO],
      grafana: String,
      name: String,
      rate: Int,
      window: Int,
  ): IO[Map[String, Double]] =
    for
      _       <- IO.sleep(window.seconds)
      entries <- metrics.toList.traverse { (key, expression) =>
                   metric(client, grafana, expression).map(key -> _)
                 }
      values  = entries.toMap
      report <- latestReport(root)
      _      <- IO.println(
             Json
               .obj(
                 "phase" -> Json.fromString(name),
                 "metrics" -> Json.obj(
                   entries.map((key, value) => key -> Json.fromDoubleOrNull(value))*,
                 ),
                 "generator" -> report,
               )
               .noSpaces,
           )
      _ <- checkWindow(name, rate, values, report)
    yield values

  private case class Span(service: String, name: String, id: String, parent: String, ms: Double)
  private val nanos: Decoder[Long] =
    Decoder[Long].or(Decoder[String].emap(_.toLongOption.toRight("Expected nanosecond timestamp")))

  private def spans(trace: Json): Decoder.Result[Vector[Span]] =
    for
      batches <- trace.hcursor.get[Vector[Json]]("batches")
      decoded <- batches.traverse { batch =>
                   for
                     attributes <-
                       batch.hcursor.downField("resource").get[Vector[Json]]("attributes")
                     serviceAttribute <-
                       attributes
                         .find(_.hcursor.get[String]("key").contains("service.name"))
                         .toRight(DecodingFailure("Missing service.name", batch.hcursor.history))
                     service <-
                       serviceAttribute.hcursor.downField("value").get[String]("stringValue")
                     scopes        <- batch.hcursor.get[Vector[Json]]("scopeSpans")
                     decodedScopes <- scopes.traverse { scope =>
                                        for
                                          values       <- scope.hcursor.get[Vector[Json]]("spans")
                                          decodedSpans <- values.traverse { value =>
                                                            val cursor = value.hcursor
                                                            for
                                                              name   <- cursor.get[String]("name")
                                                              id     <- cursor.get[String]("spanId")
                                                              parent <- cursor.get[Option[String]](
                                                                          "parentSpanId",
                                                                        )
                                                              start <- cursor.get[Long](
                                                                         "startTimeUnixNano",
                                                                       )(using nanos)
                                                              end <- cursor.get[Long](
                                                                       "endTimeUnixNano",
                                                                     )(using nanos)
                                                            yield Span(
                                                              service,
                                                              name,
                                                              id,
                                                              parent.getOrElse(""),
                                                              (end - start) / 1e6,
                                                            )
                                                          }
                                        yield decodedSpans
                                      }
                   yield decodedScopes.flatten
                 }
    yield decoded.flatten

  private def matchesLatency(client: Span, server: Span, slow: Boolean): Boolean =
    if slow then client.ms - server.ms > 500 else client.ms < 100

  private[lab] def matchingBoundaryTrace(
      id: String,
      trace: Json,
      slow: Boolean,
  ): Decoder.Result[Option[(String, Double, Double)]] =
    spans(trace).map { all =>
      val clients = all.filter(s => s.service == "gateway-service" && s.name == "GET /courses")
      val servers = all.filter(s => s.service == "catalog-service" && s.name == "GET /courses")
      for
        client <- clients.headOption if clients.size == 1
        server <- servers.headOption if servers.size == 1
        if server.parent == client.id
        if matchesLatency(client, server, slow)
      yield (id, client.ms, server.ms)
    }

  private def boundaryTrace(
      client: Client[IO],
      grafana: String,
      start: Instant,
      slow: Boolean,
  ): IO[Unit] =
    for
      now      <- IO.realTimeInstant
      condition = if slow then "> 500ms}" else "< 100ms}"
      result   <- getJson(
                  client,
                  query(
                    grafana,
                    "/api/datasources/proxy/uid/tempo/api/search",
                    "q" -> ("{resource.service.name=\"gateway-service\" && duration " + condition),
                    "start" -> start.getEpochSecond.toString,
                    "end" -> now.getEpochSecond.toString,
                    "limit" -> "10",
                  ),
                )
      candidates <- IO.fromEither(result.hcursor.get[Vector[Json]]("traces"))
      matches    <- candidates.traverse { candidate =>
                   for
                     id    <- IO.fromEither(candidate.hcursor.get[String]("traceID"))
                     trace <- getJson(
                                client,
                                query(grafana, s"/api/datasources/proxy/uid/tempo/api/traces/$id"),
                              )
                     matched <- IO.fromEither(matchingBoundaryTrace(id, trace, slow))
                   yield matched
                 }
      matched <- IO.fromOption(matches.flatten.headOption)(
                   new IllegalStateException("No matching distributed boundary trace"),
                 )
      (id, clientMs, serverMs) = matched
      _                       <- IO.println(
             Json
               .obj(
                 "trace_phase" -> Json.fromString(if slow then "fault" else "recovery"),
                 "trace_id" -> Json.fromString(id),
                 "client_ms" -> Json.fromDoubleOrNull(clientMs),
                 "catalog_server_ms" -> Json.fromDoubleOrNull(serverMs),
               )
               .noSpaces,
           )
    yield ()

  def scenario1(root: Path, grafana: String, rate: Int, window: Int): IO[Unit] =
    val resources = for
      _ <- Resource.eval(LabCommands.requireIdle(root) *> LabScenarios.requireNoActiveChange(root))
      client <- httpClient
      _      <- LabCommands.trafficResource(root, Seq("--rate", rate.toString))
      _      <- Resource.eval(LabCommands.proxy(root, ProxyAction.Reset))
    yield client
    resources.use { client =>
      for
        baseline <- observe(root, client, grafana, "baseline", rate, window)
        _        <- requireThat(
               baseline("client_p95") < 0.2 && baseline("gateway_p95") < 0.2,
               "Baseline Catalog latency is too high",
             )
        _ <- LabScenarios.faultResource(root, 750).use { id =>
               for
                 start <- IO.realTimeInstant
                 _     <- IO.println(s"Applied $id")
                 fault <- observe(root, client, grafana, "fault", rate, window)
                 _     <- requireThat(
                        fault("client_p95") > baseline("client_p95") + 0.4 &&
                          fault("gateway_p95") > baseline("gateway_p95") + 0.4 && fault(
                            "catalog_p95",
                          ) < 0.15,
                        "Fault latency did not match Scenario 1",
                      )
                 _ <- boundaryTrace(client, grafana, start, slow = true)
               yield ()
             }
        recoveryStart <- IO.realTimeInstant
        recovery      <- observe(root, client, grafana, "recovery", rate, window)
        _             <- requireThat(
               recovery("client_p95") < 0.2 && recovery("gateway_p95") < 0.2 && recovery(
                 "catalog_p95",
               ) < 0.15,
               "Catalog latency did not recover",
             )
        _ <- boundaryTrace(client, grafana, recoveryStart, slow = false)
        _ <- IO.println("Scenario 1 passed")
      yield ()
    }

  private def summary(result: LabIo.ProcessResult): IO[Json] =
    for
      _ <- requireThat(
             Set(0, 1, 2)(result.exitCode),
             s"Traffic process exited with ${result.exitCode}: ${result.stderr.takeRight(1000)}",
           )
      report <-
        IO.fromOption(
          result.stdout.linesIterator
            .flatMap(line => parse(line).toOption)
            .find(_.hcursor.get[String]("type").contains("summary")),
        )(
          new IllegalStateException(
            s"Generator produced no summary (exit ${result.exitCode}): ${result.stderr.takeRight(1000)}",
          ),
        )
    yield report

  private def sample(root: Path, loginPercent: Int, attempts: Int = 3): IO[Json] =
    for
      result <- LabCommands.trafficRunCaptured(
                  root,
                  Seq(
                    "--profile",
                    "identity",
                    "--rate",
                    "30",
                    "--duration",
                    "25s",
                    "--login-percent",
                    loginPercent.toString,
                  ),
                )
      report    <- summary(result)
      valid     <- IO.fromEither(report.hcursor.get[Boolean]("load_valid"))
      failed    <- IO.fromEither(report.hcursor.get[Long]("failed"))
      succeeded <- IO.fromEither(report.hcursor.get[Long]("succeeded"))
      accepted  <-
        if valid && failed == 0 && succeeded >= 700 then IO.pure(report)
        else if attempts > 1 then
          IO.println(s"Retrying $loginPercent% mix after invalid load") *> sample(
            root,
            loginPercent,
            attempts - 1,
          )
        else
          IO.raiseError(new IllegalStateException(s"Traffic remained invalid: ${report.noSpaces}"))
    yield accepted

  def scenario3(root: Path): IO[Unit] =
    for
      _          <- LabCommands.requireIdle(root)
      deployment <- LabCommands.deployment(root, "identity-service")
      _          <- IO.println(s"Verifying Identity behavior: $deployment")
      baseline   <- sample(root, 5)
      fault      <- sample(root, 70)
      before     <- operationMean(baseline, "identity-current-user")
      after      <- operationMean(fault, "identity-current-user")
      _          <-
        IO.println(f"Current-user mean latency: $before%.1f ms baseline, $after%.1f ms heavy mix")
      _ <- requireThat(
             after >= before * 2,
             "The unrelated Identity slowdown was too small on this machine",
           )
      _ <- IO.println("Scenario 3 workload check passed")
    yield ()

  private def catalogSample(root: Path, profile: String, duration: String): IO[Json] =
    LabCommands
      .trafficRunCaptured(
        root,
        Seq("--profile", profile, "--rate", "5", "--duration", duration, "--request-timeout", "15s"),
      )
      .flatMap(summary)

  private def operationMean(report: Json, operation: String): IO[Double] =
    IO.fromEither(
      report.hcursor.downField("operations").downField(operation).get[Double]("latency_mean_ms"),
    )

  def scenario5(root: Path, grafana: String): IO[Unit] =
    val resources = for
      _      <- Resource.eval(LabCommands.requireIdle(root))
      client <- httpClient
      _      <-
        LabCommands.rebuilt(root, "catalog-service", Map("CATALOG_POSTGRES_MAX_CONNECTIONS" -> "6"))
    yield client
    resources.use { client =>
      for
        deployment <- LabCommands.deployment(root, "catalog-service")
        _          <- IO.println(s"Verifying Catalog behavior: $deployment")
        id         <- LabCommands.containerId(root, "catalog-service")
        instance   <-
          LabIo
            .output(root, Seq("docker", "inspect", "--format", "{{.Config.Hostname}}", id))
            .map(_.trim)
        active =
          s"""catalog_session_active{service_name="catalog-service",service_instance_id="$instance"}"""
        waitAge =
          s"""catalog_session_wait_max_age_seconds{service_name="catalog-service",service_instance_id="$instance"}"""
        baseline <- catalogSample(root, "catalog-reads", "25s")
        _        <- requireThat(
               baseline.hcursor
                 .get[Boolean]("load_valid")
                 .contains(true) && baseline.hcursor.get[Long]("failed").contains(0L),
               "Scenario 5 baseline was unhealthy",
             )
        baselineActive <- metric(client, grafana, active)
        baselineWait   <- metric(client, grafana, waitAge)
        _              <- requireThat(
               baselineActive < 1 && baselineWait < 0.5,
               "Catalog retained a baseline session or waiter",
             )
        _ <- LabCommands
               .trafficResource(
                 root,
                 Seq("--profile", "catalog-soak", "--rate", "5", "--request-timeout", "15s"),
               )
               .use { _ =>
                 for
                   _           <- IO.sleep(95.seconds)
                   fault       <- latestReport(root)
                   faultActive <- metric(client, grafana, active)
                   faultWait   <- metric(client, grafana, waitAge)
                   before      <- operationMean(baseline, "catalog-learning-paths")
                   after       <- operationMean(fault, "catalog-learning-paths")
                   _           <- IO.println(
                          Json
                            .obj(
                              "baseline" -> baseline,
                              "fault" -> fault,
                              "fault_active" -> Json.fromDoubleOrNull(faultActive),
                              "fault_wait_age_seconds" -> Json.fromDoubleOrNull(faultWait),
                            )
                            .noSpaces,
                        )
                   _ <- requireThat(
                          fault.hcursor.get[Boolean]("load_valid").contains(true) &&
                            fault.hcursor
                              .get[Long]("failed")
                              .exists(_ > 0) && faultActive >= 5 && faultWait > baselineWait + 5 &&
                            after > before * 2 && after > 1000,
                          "Scenario 5 did not show valid-load pool depletion and unrelated read slowdown",
                        )
                 yield ()
               }
        _           <- IO.sleep(18.seconds)
        cleanupWait <- metric(client, grafana, waitAge)
        _           <- IO.println(
               Json
                 .obj(
                   "phase" -> Json.fromString("cleanup"),
                   "remaining_wait_age_seconds" -> Json.fromDoubleOrNull(cleanupWait),
                 )
                 .noSpaces,
             )
        _ <- IO.println("Scenario 5 pool-depletion check passed")
      yield ()
    }
