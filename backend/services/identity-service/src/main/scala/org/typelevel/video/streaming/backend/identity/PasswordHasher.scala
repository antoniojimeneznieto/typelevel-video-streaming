package org.typelevel.video.streaming.backend.identity.service

import java.nio.charset.StandardCharsets

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.syntax.all.*
import com.password4j.types.Argon2
import com.password4j.{Argon2Function, SaltGenerator}
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import org.typelevel.video.streaming.backend.identity.domain.{NewPassword, Password, PasswordHash}

trait PasswordHasher:

  def hash(password: NewPassword): IO[PasswordHash]

  def verify(password: Password, hash: PasswordHash): IO[Boolean]

final class PasswordHasherImpl private (
    function: Argon2Function,
    permits: Semaphore[IO],
    telemetry: PasswordHasherTelemetry,
) extends PasswordHasher:

  override def hash(password: NewPassword): IO[PasswordHash] =
    permits.permit
      .use(_ =>
        IO.blocking {
          function
            .hash(
              NewPassword.value(password).getBytes(StandardCharsets.UTF_8),
              SaltGenerator.generate(16),
            )
            .getResult
        },
      )
      .flatMap { hash =>
        PasswordHash(hash)
          .leftMap(new IllegalStateException(_))
          .liftTo[IO]
      }

  override def verify(password: Password, hash: PasswordHash): IO[Boolean] =
    val work =
      IO.delay {
        val encodedHash = PasswordHash.value(hash)

        Argon2Function
          .getInstanceFromHash(encodedHash)
          .check(Password.value(password), encodedHash)
      }
    telemetry.verify(permits)(work)

object PasswordHasherImpl:

  private val MemoryKiB    = 19 * 1024
  private val Iterations   = 2
  private val Parallelism  = 1
  private val OutputLength = 32

  def create(
      maxConcurrent: Int = 4,
  )(using TracerProvider[IO], MeterProvider[IO]): IO[PasswordHasherImpl] =
    require(maxConcurrent > 0, "maxConcurrent must be positive")
    for
      telemetry <- PasswordHasherTelemetry.create
      permits   <- Semaphore[IO](maxConcurrent.toLong)
    yield new PasswordHasherImpl(
      Argon2Function.getInstance(
        MemoryKiB,
        Iterations,
        Parallelism,
        OutputLength,
        Argon2.ID,
      ),
      permits,
      telemetry,
    )
