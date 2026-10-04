package org.typelevel.video.streaming.lab

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse

import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.{Duration, Instant}
import scala.concurrent.duration.*

private[lab] object LabVerify {
  private val container = "typelevel-video-streaming-lab-traffic"

  private val metrics = Map(
    "offered" -> """sum(rate(lab_traffic_arrivals_total{operation="catalog-courses"}[30s]))""",
    "sent" -> """sum(rate(lab_traffic_arrivals_total{operation="catalog-courses",result="sent"}[30s]))""",
    "gateway_rate" -> """sum(rate(http_server_request_duration_seconds_count{service_name="gateway-service",classifier="/api/catalog/courses",http_phase="body"}[30s]))""",
    "client_rate" -> """sum(rate(http_client_request_duration_seconds_count{service_name="gateway-service",classifier="catalog:/courses",http_phase="body"}[30s]))""",
    "catalog_rate" -> """sum(rate(http_server_request_duration_seconds_count{service_name="catalog-service",classifier="/courses",http_phase="body"}[30s]))""",
    "gateway_p95" -> """histogram_quantile(0.95,sum by (le) (rate(http_server_request_duration_seconds_bucket{service_name="gateway-service",classifier="/api/catalog/courses",http_phase="body"}[30s])))""",
    "client_p95" -> """histogram_quantile(0.95,sum by (le) (rate(http_client_request_duration_seconds_bucket{service_name="gateway-service",classifier="catalog:/courses",http_phase="body"}[30s])))""",
    "catalog_p95" -> """histogram_quantile(0.95,sum by (le) (rate(http_server_request_duration_seconds_bucket{service_name="catalog-service",classifier="/courses",http_phase="body"}[30s])))""",
  )

  private def requireThat(condition: Boolean, message: String): IO[Unit] =
    IO.raiseUnless(condition)(new IllegalStateException(message))

  private def getJson(client: HttpClient, url: String): IO[Json] = {
    val request =
      HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build()
    IO.blocking(client.send(request, HttpResponse.BodyHandlers.ofString())).flatMap { response =>
      requireThat(response.statusCode() == 200, s"HTTP ${response.statusCode()}: $url") *>
        IO.fromEither(parse(response.body()))
    }
  }

  private def query(base: String, path: String, parameters: (String, String)*): String = {
    val suffix = parameters
      .map { case (key, value) =>
        s"${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
      }
      .mkString("&")
    base.stripSuffix("/") + path + (if suffix.isEmpty then "" else s"?$suffix")
  }

  private def metric(client: HttpClient, grafana: String, expression: String): IO[Double] =
    getJson(
      client,
      query(grafana, "/api/datasources/proxy/uid/prometheus/api/v1/query", "query" -> expression),
    ).flatMap { json =>
      IO {
        val values = json.hcursor
          .downField("data")
          .get[Vector[Json]]("result")
          .getOrElse(throw new IllegalStateException(s"Missing Prometheus result: $expression"))
        require(values.size == 1, s"Expected one metric series, got ${values.size}: $expression")
        val pair = values.head.hcursor
          .get[Vector[Json]]("value")
          .getOrElse(throw new IllegalStateException(s"Missing Prometheus value: $expression"))
        pair(1).asString
          .getOrElse(throw new IllegalStateException("Invalid Prometheus value"))
          .toDouble
      }
    }

  private def latestReport(root: Path): IO[Json] =
    LabIo.run(root, Seq("docker", "logs", "--tail", "10", container), capture = true).flatMap {
      output =>
        IO.fromOption(
          output.linesIterator.toVector.reverse
            .flatMap(line => parse(line).toOption)
            .find(_.hcursor.get[String]("type").contains("progress")),
        )(
          new IllegalStateException("No traffic progress report found"),
        )
    }

  private[lab] def checkWindow(
      name: String,
      rate: Int,
      values: Map[String, Double],
      report: Json,
  ): IO[Unit] = {
    val valid  = report.hcursor.get[Boolean]("load_valid").getOrElse(false)
    val failed = report.hcursor.get[Long]("failed").getOrElse(-1L)
    requireThat(valid && failed == 0, s"$name: generator lost or failed requests") *>
      List("offered", "sent", "gateway_rate", "client_rate", "catalog_rate").traverse_ { key =>
        val value = values(key)
        requireThat(
          value >= rate * 0.8 && value <= rate * 1.2,
          s"$name: $key rate diverged ($value)",
        )
      }
  }

  private def observe(
      root: Path,
      client: HttpClient,
      grafana: String,
      name: String,
      rate: Int,
      window: Int,
  ): IO[Map[String, Double]] =
    IO.sleep(window.seconds) *>
      metrics.toList
        .traverse { case (key, expression) =>
          metric(client, grafana, expression).map(key -> _)
        }
        .map(_.toMap)
        .flatMap { values =>
          latestReport(root).flatMap { report =>
            IO.println(
              Json
                .obj(
                  "phase" -> Json.fromString(name),
                  "metrics" -> Json.obj(values.toSeq.map { case (key, value) =>
                    key -> Json.fromDoubleOrNull(value)
                  }*),
                  "generator" -> report,
                )
                .noSpaces,
            ) *> checkWindow(name, rate, values, report).as(values)
          }
        }

  private case class Span(service: String, name: String, id: String, parent: String, ms: Double)

  private def matchesLatency(client: Span, server: Span, slow: Boolean): Boolean =
    if slow then client.ms - server.ms > 500 else client.ms < 100

  private def spans(trace: Json): Vector[Span] = {
    def string(json: Json, key: String): String =
      json.hcursor.get[String](key).getOrElse(throw new IllegalStateException(s"Missing $key"))
    def nanos(json: Json, key: String): Long =
      json.hcursor
        .get[String](key)
        .map(_.toLong)
        .orElse(json.hcursor.get[Long](key))
        .getOrElse(throw new IllegalStateException(s"Missing $key"))
    val batches = trace.hcursor.get[Vector[Json]]("batches").getOrElse(Vector.empty)
    batches.flatMap { batch =>
      val attributes = batch.hcursor
        .downField("resource")
        .get[Vector[Json]]("attributes")
        .getOrElse(Vector.empty)
      val service = attributes
        .find(_.hcursor.get[String]("key").contains("service.name"))
        .flatMap(_.hcursor.downField("value").get[String]("stringValue").toOption)
        .getOrElse("")
      batch.hcursor.get[Vector[Json]]("scopeSpans").getOrElse(Vector.empty).flatMap { scope =>
        scope.hcursor.get[Vector[Json]]("spans").getOrElse(Vector.empty).map { span =>
          Span(
            service,
            string(span, "name"),
            string(span, "spanId"),
            span.hcursor.get[String]("parentSpanId").getOrElse(""),
            (nanos(span, "endTimeUnixNano") - nanos(span, "startTimeUnixNano")) / 1e6,
          )
        }
      }
    }
  }

  private[lab] def matchingBoundaryTrace(
      id: String,
      trace: Json,
      slow: Boolean,
  ): Option[(String, Double, Double)] = {
    val all     = spans(trace)
    val clients = all.filter(s => s.service == "gateway-service" && s.name == "GET /courses")
    val servers = all.filter(s => s.service == "catalog-service" && s.name == "GET /courses")
    for {
      client <- clients.headOption if clients.size == 1
      server <- servers.headOption if servers.size == 1
      if server.parent == client.id
      if matchesLatency(client, server, slow)
    } yield (id, client.ms, server.ms)
  }

  private def boundaryTrace(
      client: HttpClient,
      grafana: String,
      start: Instant,
      slow: Boolean,
  ): IO[Unit] = {
    val condition = if slow then "> 500ms}" else "< 100ms}"
    val search    = query(
      grafana,
      "/api/datasources/proxy/uid/tempo/api/search",
      "q" -> ("{resource.service.name=\"gateway-service\" && duration " + condition),
      "start" -> start.getEpochSecond.toString,
      "end" -> Instant.now().getEpochSecond.toString,
      "limit" -> "10",
    )
    getJson(client, search).flatMap { result =>
      val candidates = result.hcursor.get[Vector[Json]]("traces").getOrElse(Vector.empty)
      candidates.toList
        .traverse { candidate =>
          val id = candidate.hcursor
            .get[String]("traceID")
            .getOrElse(throw new IllegalStateException("Missing traceID"))
          getJson(client, query(grafana, s"/api/datasources/proxy/uid/tempo/api/traces/$id"))
            .map(trace => id -> trace)
        }
        .flatMap { traces =>
          val matchFound = traces.collectFirst(Function.unlift { case (id, trace) =>
            matchingBoundaryTrace(id, trace, slow)
          })
          IO.fromOption(matchFound)(
            new IllegalStateException(
              s"No matching distributed trace for ${if slow then "fault" else "recovery"}",
            ),
          ).flatMap { case (id, clientMs, serverMs) =>
            IO.println(
              Json
                .obj(
                  "trace_phase" -> Json.fromString(if slow then "fault" else "recovery"),
                  "trace_id" -> Json.fromString(id),
                  "client_ms" -> Json.fromDoubleOrNull(clientMs),
                  "catalog_server_ms" -> Json.fromDoubleOrNull(serverMs),
                )
                .noSpaces,
            )
          }
        }
    }
  }

  def scenario1(root: Path, grafana: String, rate: Int, window: Int): IO[Unit] = {
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    Ref.of[IO, Option[String]](None).flatMap { activeChange =>
      val check = for {
        _        <- LabCommands.proxy(root, "reset", None)
        _        <- LabCommands.trafficStart(root, Seq("--rate", rate.toString))
        baseline <- observe(root, client, grafana, "baseline", rate, window)
        _        <- requireThat(
               baseline("client_p95") < 0.2 && baseline("gateway_p95") < 0.2,
               "Baseline Catalog latency is too high",
             )
        faultStart <- IO.realTimeInstant
        id         <- LabScenarios.activatePlatformChange(root, 750)
        _          <- activeChange.set(Some(id)) *> IO.println(s"Applied $id")
        fault      <- observe(root, client, grafana, "fault", rate, window)
        _          <- requireThat(
               fault("client_p95") > baseline("client_p95") + 0.4 &&
                 fault("gateway_p95") > baseline("gateway_p95") + 0.4 &&
                 fault("catalog_p95") < 0.15,
               "Fault latency did not match Scenario 1",
             )
        _             <- boundaryTrace(client, grafana, faultStart, slow = true)
        _             <- LabScenarios.platform(root, "rollback", Some(id))
        _             <- activeChange.set(None)
        recoveryStart <- IO.realTimeInstant
        recovery      <- observe(root, client, grafana, "recovery", rate, window)
        _             <- requireThat(
               recovery("client_p95") < 0.2 && recovery("gateway_p95") < 0.2 &&
                 recovery("catalog_p95") < 0.15,
               "Catalog latency did not recover",
             )
        _ <- boundaryTrace(client, grafana, recoveryStart, slow = false)
        _ <- IO.println("Scenario 1 passed")
      } yield ()
      check.guarantee(
        activeChange.get
          .flatMap(_.traverse_(id => LabScenarios.platform(root, "rollback", Some(id))))
          .guarantee(
            LabCommands.stopTraffic(root).guarantee(LabCommands.proxy(root, "reset", None)),
          ),
      )
    }
  }

  private def summary(output: String): IO[Json] =
    IO.fromOption(
      output.linesIterator
        .flatMap(line => parse(line).toOption)
        .find(_.hcursor.get[String]("type").contains("summary")),
    )(
      new IllegalStateException(s"Generator produced no summary: ${output.takeRight(1000)}"),
    )

  private def sample(root: Path, loginPercent: Int, attempts: Int = 3): IO[Json] =
    LabCommands
      .trafficRunCaptured(
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
      .flatMap(summary)
      .flatMap { report =>
        val valid     = report.hcursor.get[Boolean]("load_valid").getOrElse(false)
        val failed    = report.hcursor.get[Long]("failed").getOrElse(-1L)
        val succeeded = report.hcursor.get[Long]("succeeded").getOrElse(0L)
        if valid && failed == 0 && succeeded >= 700 then IO.pure(report)
        else if attempts > 1 then
          IO.println(s"Retrying $loginPercent% mix after invalid load (${4 - attempts}/3)") *>
            sample(root, loginPercent, attempts - 1)
        else
          IO.raiseError(new IllegalStateException(s"Traffic remained invalid: ${report.noSpaces}"))
      }

  def scenario3(root: Path): IO[Unit] = {
    val source = root.resolve(
      "backend/services/identity-service/src/main/scala/org/typelevel/video/streaming/backend/identity/PasswordHasher.scala",
    )
    for {
      content <- LabIo.read(source)
      verify   = content.split("override def verify", 2).lift(1).getOrElse("")
      _       <- requireThat(
             verify.contains("IO.delay {"),
             "The checked-out Identity source is not the exercise version",
           )
      baseline <- sample(root, 5)
      fault    <- sample(root, 70)
      before   <- IO.fromEither(
                  baseline.hcursor
                    .downField("operations")
                    .downField("identity-current-user")
                    .get[Double]("latency_mean_ms"),
                )
      after <- IO.fromEither(
                 fault.hcursor
                   .downField("operations")
                   .downField("identity-current-user")
                   .get[Double]("latency_mean_ms"),
               )
      _ <-
        IO.println(f"Current-user mean latency: $before%.1f ms baseline, $after%.1f ms heavy mix")
      _ <- requireThat(
             after >= before * 2,
             "The unrelated Identity slowdown was too small on this machine",
           )
      _ <- IO.println("Scenario 3 workload check passed")
    } yield ()
  }
}
