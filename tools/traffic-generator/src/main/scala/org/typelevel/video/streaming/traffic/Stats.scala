package org.typelevel.video.streaming.traffic

import scala.concurrent.duration.*

import io.circe.Json

final case class RequestResult(
    status: Option[Int],
    outcome: String,
    operation: String = "catalog-courses",
)

final case class OperationStats(count: Long = 0L, latencyNanos: Long = 0L):
  def add(elapsed: FiniteDuration): OperationStats =
    copy(count = count + 1, latencyNanos = latencyNanos + elapsed.toNanos)

  def json: Json = Json.obj(
    "count" -> Json.fromLong(count),
    "latency_mean_ms" -> Json.fromDoubleOrNull(latencyNanos.toDouble / count.toDouble / 1e6),
  )

/** Only bounded outcome names and HTTP status codes are retained. No request data or exceptions. */
final case class Stats(
    offered: Long                           = 0L,
    started: Long                           = 0L,
    completed: Long                         = 0L,
    succeeded: Long                         = 0L,
    cancelled: Long                         = 0L,
    droppedCapacity: Long                   = 0L,
    droppedLate: Long                       = 0L,
    peakInFlight: Long                      = 0L,
    statuses: Map[Int, Long]                = Map.empty,
    outcomes: Map[String, Long]             = Map.empty,
    latencyNanos: Long                      = 0L,
    maxLatencyNanos: Long                   = 0L,
    operations: Map[String, OperationStats] = Map.empty,
):
  def inFlight: Long = started - completed - cancelled
  def failed: Long   = completed - succeeded
  def valid: Boolean = droppedCapacity == 0 && droppedLate == 0 && cancelled == 0

  def start: Stats = copy(started = started + 1, peakInFlight = peakInFlight.max(inFlight + 1))

  def finish(result: RequestResult, elapsed: FiniteDuration): Stats = copy(
    completed = completed + 1,
    succeeded = succeeded + (if result.outcome == "success" then 1 else 0),
    statuses  =
      result.status.fold(statuses)(s => statuses.updated(s, statuses.getOrElse(s, 0L) + 1)),
    outcomes        = outcomes.updated(result.outcome, outcomes.getOrElse(result.outcome, 0L) + 1),
    latencyNanos    = latencyNanos + elapsed.toNanos,
    maxLatencyNanos = maxLatencyNanos.max(elapsed.toNanos),
    operations      = operations.updated(
      result.operation,
      operations.getOrElse(result.operation, OperationStats()).add(elapsed),
    ),
  )

  def window(previous: Stats): Json =
    val late     = droppedLate - previous.droppedLate
    val capacity = droppedCapacity - previous.droppedCapacity
    val stopped  = cancelled - previous.cancelled
    Json.obj(
      "offered" -> Json.fromLong(offered - previous.offered),
      "started" -> Json.fromLong(started - previous.started),
      "completed" -> Json.fromLong(completed - previous.completed),
      "failed" -> Json.fromLong(failed - previous.failed),
      "dropped_late" -> Json.fromLong(late),
      "dropped_capacity" -> Json.fromLong(capacity),
      "load_valid" -> Json.fromBoolean(late == 0 && capacity == 0 && stopped == 0),
    )

  def json(kind: String, elapsed: FiniteDuration, profile: String = "catalog-courses"): Json =
    Json.obj(
      "type" -> Json.fromString(kind),
      "profile" -> Json.fromString(profile),
      "elapsed_seconds" -> Json.fromDoubleOrNull(elapsed.toNanos.toDouble / 1e9),
      "offered" -> Json.fromLong(offered),
      "started" -> Json.fromLong(started),
      "completed" -> Json.fromLong(completed),
      "succeeded" -> Json.fromLong(succeeded),
      "failed" -> Json.fromLong(failed),
      "cancelled" -> Json.fromLong(cancelled),
      "in_flight" -> Json.fromLong(inFlight),
      "peak_in_flight" -> Json.fromLong(peakInFlight),
      "dropped_capacity" -> Json.fromLong(droppedCapacity),
      "dropped_late" -> Json.fromLong(droppedLate),
      "load_valid" -> Json.fromBoolean(valid),
      "statuses" -> Json.obj(
        statuses.toList.sortBy(_._1).map((k, v) => k.toString -> Json.fromLong(v))*,
      ),
      "outcomes" -> Json.obj(outcomes.toList.sortBy(_._1).map((k, v) => k -> Json.fromLong(v))*),
      "operations" -> Json.obj(operations.toList.sortBy(_._1).map((k, v) => k -> v.json)*),
      "latency_count" -> Json.fromLong(completed),
      "latency_sum_ms" -> Json.fromDoubleOrNull(latencyNanos.toDouble / 1e6),
      "latency_mean_ms" -> (if completed == 0 then Json.Null
                            else
                              Json.fromDoubleOrNull(
                                latencyNanos.toDouble / completed.toDouble / 1e6,
                              )),
      "latency_max_ms" -> (if completed == 0 then Json.Null
                           else Json.fromDoubleOrNull(maxLatencyNanos.toDouble / 1e6)),
    )
