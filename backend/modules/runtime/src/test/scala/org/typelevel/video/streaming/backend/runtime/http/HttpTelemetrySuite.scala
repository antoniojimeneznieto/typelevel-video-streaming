package org.typelevel.video.streaming.backend.runtime.http

import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*

import cats.effect.{IO, Resource}
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.`export`.{SimpleSpanProcessor, SpanExporter}
import org.http4s.{Header, HttpApp, Method, Request, Response, Status, Uri}
import org.http4s.server.middleware.CORS
import org.typelevel.ci.CIString
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.TracerProvider
import weaver.SimpleIOSuite

object HttpTelemetrySuite extends SimpleIOSuite:

  private given MeterProvider[IO]  = MeterProvider.noop[IO]
  private given TracerProvider[IO] = TracerProvider.noop[IO]

  test("no-op telemetry preserves the request and response") {
    val uri = Uri.unsafeFromString("/courses/private-id?email=alice%40example.com")
    val app =
      HttpApp[IO](request => IO.pure(Response[IO](Status.Ok).withEntity(request.uri.renderString)))

    for
      instrumented <- HttpTelemetry(app)
      response     <- instrumented(Request[IO](uri = uri))
      body         <- response.as[String]
    yield expect(response.status == Status.Ok) and expect(body == uri.renderString)
  }

  test("telemetry preserves authentication failures and challenges") {
    val app = HttpApp[IO](_ =>
      IO.pure(
        Response[IO](Status.Unauthorized)
          .putHeaders(Header.Raw(CIString("WWW-Authenticate"), "Bearer")),
      ),
    )

    for
      instrumented <- HttpTelemetry(app)
      response     <- instrumented(Request[IO]())
      _            <- response.body.compile.drain
    yield expect(response.status == Status.Unauthorized) and
      expect(response.headers.get(CIString("WWW-Authenticate")).exists(_.head.value == "Bearer"))
  }

  test("telemetry preserves application errors") {
    val failure = new IllegalStateException("test failure")
    val app     = HttpApp[IO](_ => IO.raiseError[Response[IO]](failure))

    for
      instrumented <- HttpTelemetry(app)
      response     <- instrumented(Request[IO]()).attempt
    yield expect(response == Left(failure))
  }

  test("telemetry preserves CORS preflight responses") {
    val app = CORS.policy.withAllowOriginAll(
      HttpApp[IO](_ => IO.pure(Response[IO](Status.NotFound))),
    )
    val request = Request[IO](Method.OPTIONS, Uri.unsafeFromString("/users"))
      .putHeaders(
        Header.Raw(CIString("Origin"), "http://localhost:3000"),
        Header.Raw(CIString("Access-Control-Request-Method"), "POST"),
      )

    for
      instrumented <- HttpTelemetry(app)
      response     <- instrumented(request)
      _            <- response.body.compile.drain
    yield expect(response.status.isSuccess) and
      expect(response.headers.get(CIString("Access-Control-Allow-Origin")).nonEmpty)
  }

  test(
    "server spans parent application spans without recording raw paths, queries, or credentials",
  ) {
    telemetry.use { case (otel, spans) =>
      given MeterProvider[IO]  = otel.meterProvider
      given TracerProvider[IO] = otel.tracerProvider

      val request = Request[IO](
        uri = Uri.unsafeFromString(
          "/users/private-user-id?email=alice%40example.com&token=secret-token",
        ),
      ).putHeaders(Header.Raw(CIString("Authorization"), "Bearer secret-token"))

      for
        tracer <- otel.tracerProvider.get("test-application")
        app     = HttpApp[IO](_ =>
                tracer.span("database-operation").surround(IO.pure(Response[IO](Status.Ok))),
              )
        instrumented <- HttpTelemetry(app)
        response     <- instrumented(request)
        _            <- response.body.compile.drain
        recorded     <- IO(spans.iterator().asScala.toList)
        server        = recorded.find(_.getName == "GET")
        child         = recorded.find(_.getName == "database-operation")
        attributes    = server.toList
                       .flatMap(_.getAttributes.asMap().asScala)
                       .map { case (key, value) =>
                         key.getKey -> value.toString
                       }
                       .toMap
      yield expect(server.nonEmpty) and expect(child.nonEmpty) and
        expect(
          child.exists(span => server.exists(parent => span.getParentSpanId == parent.getSpanId)),
        ) and
        expect(attributes.get("http.request.method").contains("GET")) and
        expect(attributes.get("http.response.status_code").contains("200")) and
        expect(!attributes.contains("url.path")) and expect(!attributes.contains("url.query")) and
        expect(
          !attributes.values.exists(value =>
            List("private-user-id", "alice", "secret-token").exists(value.contains),
          ),
        )
    }
  }

  private def telemetry: Resource[IO, (OtelJava[IO], ConcurrentLinkedQueue[SpanData])] =
    Resource.eval(IO(new ConcurrentLinkedQueue[SpanData])).flatMap { spans =>
      val exporter = new SpanExporter:
        override def `export`(batch: java.util.Collection[SpanData]): CompletableResultCode =
          val _ = spans.addAll(batch)
          CompletableResultCode.ofSuccess()

        override def flush(): CompletableResultCode    = CompletableResultCode.ofSuccess()
        override def shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()

      OtelJava
        .resource(IO {
          val provider = SdkTracerProvider
            .builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
          OpenTelemetrySdk.builder().setTracerProvider(provider).build()
        })
        .map(_ -> spans)
    }
