package org.typelevel.video.streaming.traffic

import cats.effect.IO

enum TrafficProfile(val label: String):
  case CatalogCourses extends TrafficProfile("catalog-courses")
  case CatalogReads extends TrafficProfile("catalog-reads")
  case CatalogSoak extends TrafficProfile("catalog-soak")
  case Identity extends TrafficProfile("identity")
  case Playback extends TrafficProfile("playback")

enum Operation(val label: String):
  case Courses extends Operation("catalog-courses")
  case LearningPaths extends Operation("catalog-learning-paths")
  case Login extends Operation("identity-login")
  case CurrentUser extends Operation("identity-current-user")
  case Favorites extends Operation("playback-favorites")

enum Outcome(val label: String):
  case Success extends Outcome("success")
  case HttpError extends Outcome("http_error")
  case DecodeError extends Outcome("decode_error")
  case TransportError extends Outcome("transport_error")
  case RequestError extends Outcome("request_error")
  case Timeout extends Outcome("timeout")

final case class PreparedRequest(operation: Operation, run: IO[RequestResult])

/** Maintenance has its own lifetime and is never timed as a measured request. */
final case class Workload(request: Long => PreparedRequest, maintenance: IO[Unit] = IO.never):
  def apply(slot: Long): IO[RequestResult] = request(slot).run
