package org.typelevel.video.streaming.backend.status

import cats.effect.IO
import org.http4s.dsl.io.*
import org.http4s.HttpRoutes

object Routes:

  val health: HttpRoutes[IO] =
    HttpRoutes.of[IO] { case GET -> Root / "health" =>
      Ok("ok")
    }
