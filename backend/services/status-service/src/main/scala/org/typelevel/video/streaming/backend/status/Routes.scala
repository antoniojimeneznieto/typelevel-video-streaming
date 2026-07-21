package org.typelevel.video.streaming.backend.status

import scala.concurrent.duration.*

import cats.effect.IO
import fs2.{Stream, text}
import org.http4s.dsl.Http4sDsl
import org.http4s.HttpRoutes

object Routes:

  def apply: HttpRoutes[IO] =
    val dsl = new Http4sDsl[IO] {}
    import dsl.*

    HttpRoutes.of[IO] {
      case GET -> Root / "api" / "health" =>
        Ok("ok")

      case GET -> Root / "api" / "stream" =>
        val ticks =
          Stream
            .awakeEvery[IO](1.second)
            .map(elapsed => s"tick ${elapsed.toSeconds}\n")
            .through(text.utf8.encode)

        Ok(ticks)
    }
