package org.typelevel.video.streaming.lab

import io.circe.{Decoder, Encoder, Json, JsonObject}

private[lab] enum ChangeStatus(val label: String):
  case Pending extends ChangeStatus("pending")
  case Active extends ChangeStatus("active")
  case RolledBack extends ChangeStatus("rolled_back")

final private[lab] case class PlatformChange(id: String, status: ChangeStatus, details: JsonObject):
  def json: Json = Json.fromJsonObject(
    details.add("id", Json.fromString(id)).add("status", Json.fromString(status.label)),
  )
  def rolledBack(at: String): PlatformChange =
    copy(
      status  = ChangeStatus.RolledBack,
      details = details.add("rolled_back_at", Json.fromString(at)),
    )
  def description: String =
    s"$id  ${details("applied_at").flatMap(_.asString).getOrElse("pending")}  ${status.label}  ${details("title").flatMap(_.asString).getOrElse("Traffic policy")}"

private[lab] object PlatformChange:
  given Decoder[PlatformChange] = Decoder.instance { cursor =>
    for
      id     <- cursor.get[String]("id")
      label  <- cursor.get[String]("status")
      status <- ChangeStatus.values
                  .find(_.label == label)
                  .toRight(io.circe.DecodingFailure("Unknown change status", cursor.history))
      details <- cursor.as[JsonObject]
    yield PlatformChange(id, status, details.remove("id").remove("status"))
  }
  given Encoder[PlatformChange] = Encoder.instance(_.json)
