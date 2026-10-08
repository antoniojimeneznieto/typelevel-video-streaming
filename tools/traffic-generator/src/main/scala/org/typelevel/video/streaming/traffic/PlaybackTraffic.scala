package org.typelevel.video.streaming.traffic

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import org.http4s.client.Client
import org.http4s.{Method, Request, Uri}

object PlaybackTraffic:
  private val password = "lab-playback-password-2026"

  def prepare(client: Client[IO], gateway: Uri, modernPercent: Int): IO[Workload] =
    for
      sessions <- (0 until 10).toVector.traverse { actor =>
                    val kind = if actor < 8 then "old" else "new"
                    ActorSession.prepare(
                      client,
                      ActorSession.jsonRequest(
                        Method.POST,
                        gateway / "api" / "identity" / "auth" / "login",
                        "email" -> s"lab-playback-$kind-$actor@example.invalid",
                        "password" -> password,
                      ),
                    )
                  }
      _ <- IO.println(
             Json
               .obj(
                 "event" -> Json.fromString("preparation"),
                 "profile" -> Json.fromString("playback"),
                 "identity_login_successes" -> Json.fromInt(10),
                 "legacy_subject_actors" -> Json.fromInt(8),
                 "modern_subject_actors" -> Json.fromInt(2),
               )
               .noSpaces,
           )
    yield Workload(
      slot =>
        val actor = if modernPercent == 20 && slot % 5 == 0 then 8 + ((slot / 5) % 2).toInt
        else ((slot / 2) % 8).toInt
        PreparedRequest(
          Operation.Favorites,
          ActorSession.execute(
            client,
            sessions(actor).authorize(
              Request[IO](Method.GET, gateway / "api" / "playback" / "favorites"),
            ),
            Operation.Favorites,
          ),
        )
      ,
      sessions.parTraverse_(_.maintain),
    )
