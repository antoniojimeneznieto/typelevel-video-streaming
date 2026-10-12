package org.typelevel.video.streaming.traffic

import cats.effect.IO
import org.http4s.client.Client
import org.http4s.{Method, Request, Uri}

/** A stable synthetic actor. No token, email, or password is emitted in telemetry. */
object IdentityTraffic:
  private val email    = "lab-identity-actor-v3@example.invalid"
  private val password = "lab-identity-password-2026"

  // 37 is coprime with 100: every hundred slots retain the exact requested mix,
  // while login and current-user requests occur throughout the same interval.
  private[traffic] def isLoginSlot(slot: Long, loginPercent: Int): Boolean =
    ((slot % 100) * 37) % 100 < loginPercent

  def prepare(client: Client[IO], gateway: Uri, loginPercent: Int): IO[Workload] =
    val base     = gateway / "api" / "identity"
    val register = ActorSession.jsonRequest(
      Method.POST,
      base / "users",
      "email" -> email,
      "password" -> password,
      "displayName" -> "Lab Actor",
    )
    val login = ActorSession.jsonRequest(
      Method.POST,
      base / "auth" / "login",
      "email" -> email,
      "password" -> password,
    )
    for
      registered <- client.run(register).use(r => r.body.compile.drain.as(r.status.code))
      _          <- IO.raiseUnless(registered == 201 || registered == 409)(
             new IllegalStateException(s"Identity actor registration returned $registered"),
           )
      session <- ActorSession.prepare(client, login)
    yield Workload(
      slot =>
        if isLoginSlot(slot, loginPercent) then
          PreparedRequest(
            Operation.Login,
            ActorSession.execute(client, IO.pure(login), Operation.Login),
          )
        else
          PreparedRequest(
            Operation.CurrentUser,
            ActorSession.execute(
              client,
              session.authorize(Request[IO](Method.GET, base / "users" / "me")),
              Operation.CurrentUser,
            ),
          )
      ,
      session.maintain,
    )
