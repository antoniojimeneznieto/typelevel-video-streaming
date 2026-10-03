package org.typelevel.video.streaming.traffic

import scala.concurrent.duration.*

import cats.syntax.all.*
import com.monovore.decline.{Argument, Command, Opts}
import org.http4s.Uri

object Cli:
  private val defaults = Config()

  private given Argument[Uri] = Argument.from("url") { value =>
    Uri.fromString(value).leftMap(_ => "Invalid gateway URL").toValidatedNel
  }

  private given Argument[Option[FiniteDuration]] = Argument.from("duration|infinite") { value =>
    if value == "infinite" then Option.empty[FiniteDuration].validNel
    else Argument[FiniteDuration].read(value).map(Some(_))
  }

  private def positiveDuration(
      name: String,
      help: String,
      default: FiniteDuration,
  ): Opts[FiniteDuration] =
    Opts
      .option[FiniteDuration](name, help = s"$help (default: $default)")
      .validate(s"--$name must be positive")(_ > Duration.Zero)
      .withDefault(default)

  private val baseUrl = Opts
    .option[Uri]("base-url", help = s"Gateway origin (default: ${defaults.baseUrl})")
    .validate(
      "--base-url must be an HTTP(S) gateway origin without credentials, path, query, or fragment",
    )(Config.isGatewayOrigin)
    .withDefault(defaults.baseUrl)

  private val rate = Opts
    .option[Int]("rate", help = s"HTTP requests/second (default: ${defaults.rate})")
    .validate("--rate must be between 1 and 10000 requests/second")(n => n > 0 && n <= 10000)
    .withDefault(defaults.rate)

  private val duration = Opts
    .option[Option[FiniteDuration]]("duration", help = "Arrival window, or infinite (default: 3m)")
    .validate("--duration must be positive")(_.forall(_ > Duration.Zero))
    .withDefault(defaults.duration)

  private val maxConcurrent = Opts
    .option[Int](
      "max-concurrent",
      help = s"Maximum in-flight requests (default: ${defaults.maxConcurrent})",
    )
    .validate("--max-concurrent must be positive")(_ > 0)
    .withDefault(defaults.maxConcurrent)

  // Compose help with the options so wrapper-provided defaults can precede --help.
  val command: Command[Config] = Command(
    name   = "traffic-generator",
    header =
      "Generate fixed-arrival-rate catalog traffic through the gateway. Profile: courses, limit=10; no retries. Durations accept 500ms, 30s, 3m.",
    helpFlag = false,
  ) {
    (
      baseUrl,
      rate,
      duration,
      maxConcurrent,
      positiveDuration(
        "request-timeout",
        "Total request deadline, including body",
        defaults.requestTimeout,
      ),
      positiveDuration("drain-timeout", "Grace period after arrivals stop", defaults.drainTimeout),
      positiveDuration("report-interval", "JSON progress interval", defaults.reportInterval),
      Opts
        .option[String]("profile", help = "catalog-courses or identity")
        .validate(
          "--profile must be catalog-courses or identity",
        )(Set("catalog-courses", "identity").contains)
        .withDefault(defaults.profile),
      Opts
        .option[Int]("login-percent", help = "Identity login share (default: 10)")
        .validate(
          "--login-percent must be between 0 and 100",
        )(n => n >= 0 && n <= 100)
        .withDefault(defaults.loginPercent),
    ).mapN(Config.apply) <* Opts.help.orElse(Opts(()))
  }
