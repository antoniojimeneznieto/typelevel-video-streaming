package org.typelevel.video.streaming.backend.playback

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import org.typelevel.video.streaming.backend.events.{LessonPublished, UserCreated}
import org.typelevel.video.streaming.backend.playback.config.KafkaConfig
import org.typelevel.video.streaming.backend.playback.repository.PlaybackProjectionRepository
import org.typelevel.video.streaming.backend.playback.worker.PlaybackEventWorker
import weaver.SimpleIOSuite

object PlaybackEventWorkerSuite extends SimpleIOSuite:

  private val config = KafkaConfig(
    bootstrapServers     = "localhost:9092",
    groupId              = "playback-test",
    lessonPublishedTopic = "catalog.lesson-published",
    userCreatedTopic     = "identity.user-created",
  )

  private val eventId    = "00000000-0000-0000-0000-000000000001"
  private val userId     = "00000000-0000-0000-0000-000000000002"
  private val courseId   = "00000000-0000-0000-0000-000000000104"
  private val occurredAt = "2026-09-07T12:00:00Z"

  private val userCreated = s"""{
    "eventId": "$eventId",
    "occurredAt": "$occurredAt",
    "userId": "$userId"
  }"""

  private val lessonPublished = s"""{
    "eventId": "$eventId",
    "occurredAt": "$occurredAt",
    "courseId": "$courseId",
    "lessonId": "lesson-1",
    "title": "Threads at Scale",
    "durationSeconds": 1849,
    "isPreview": true,
    "objectKey": "courses/$courseId/lesson-1.mp4"
  }"""

  test("UserCreated is decoded and persisted before its offset is committed") {
    for
      actions   <- Ref.of[IO, Vector[String]](Vector.empty)
      received  <- Ref.of[IO, Option[UserCreated]](None)
      repository = new RecordingRepository(actions):
                     override def userCreated(event: UserCreated): IO[Unit] =
                       received.set(Some(event)) *> super.userCreated(event)
      worker = new PlaybackEventWorker(config, repository)
      _     <-
        worker.process(config.userCreatedTopic, 2, 42L, userCreated, actions.update(_ :+ "commit"))
      observed <- actions.get
      event    <- received.get
    yield expect.all(
      observed == Vector("user", "commit"),
      event.exists(_.eventId.value.toString == eventId),
      event.exists(_.userId.value.toString == userId),
      event.exists(_.occurredAt.toInstant.toString == occurredAt),
    )
  }

  test("LessonPublished is decoded and persisted before its offset is committed") {
    for
      actions   <- Ref.of[IO, Vector[String]](Vector.empty)
      received  <- Ref.of[IO, Option[LessonPublished]](None)
      repository = new RecordingRepository(actions):
                     override def lessonPublished(event: LessonPublished): IO[Unit] =
                       received.set(Some(event)) *> super.lessonPublished(event)
      worker = new PlaybackEventWorker(config, repository)
      _     <- worker.process(
             config.lessonPublishedTopic,
             2,
             42L,
             lessonPublished,
             actions.update(_ :+ "commit"),
           )
      observed <- actions.get
      event    <- received.get
    yield expect.all(
      observed == Vector("lesson", "commit"),
      event.exists(_.eventId.value.toString == eventId),
      event.exists(_.occurredAt.toInstant.toString == occurredAt),
      event.exists(_.courseId.value.toString == courseId),
      event.exists(_.lessonId.value == "lesson-1"),
      event.exists(_.title.value == "Threads at Scale"),
      event.exists(_.durationSeconds.value == 1849),
      event.exists(_.isPreview),
      event.exists(_.objectKey.value == s"courses/$courseId/lesson-1.mp4"),
    )
  }

  test("invalid events stop processing without persistence, commits, or payload leakage") {
    val invalid = List(
      config.userCreatedTopic -> "not-json-private-payload",
      config.userCreatedTopic -> "{}",
      config.userCreatedTopic -> userCreated.replace(userId, "private-invalid-uuid"),
      config.lessonPublishedTopic -> lessonPublished.replace("1849", "-1"),
      config.lessonPublishedTopic -> lessonPublished.replace("lesson-1", "INVALID LESSON"),
      config.userCreatedTopic -> null,
      config.userCreatedTopic -> "null",
      "unknown-topic" -> userCreated,
    )

    invalid
      .traverse { case (topic, value) =>
        for
          actions <- Ref.of[IO, Vector[String]](Vector.empty)
          worker   = new PlaybackEventWorker(config, new RecordingRepository(actions))
          result  <- worker
                      .process(topic, 2, 42L, value, actions.update(_ :+ "commit"))
                      .attempt
          observed <- actions.get
        yield expect.all(
          observed.isEmpty,
          result.left.exists(_.isInstanceOf[IllegalArgumentException]),
          result.left.exists(
            _.getMessage == s"Invalid playback event at topic=$topic partition=2 offset=42",
          ),
          result.left.exists(_.getCause == null),
        )
      }
      .map(_.reduce(_ and _))
  }

  test("database failure propagates without committing the record") {
    val failure = new RuntimeException("Database unavailable")

    for
      actions   <- Ref.of[IO, Vector[String]](Vector.empty)
      repository = new RecordingRepository(actions):
                     override def userCreated(event: UserCreated): IO[Unit] =
                       super.userCreated(event) *> IO.raiseError(failure)
      worker  = new PlaybackEventWorker(config, repository)
      result <-
        worker
          .process(config.userCreatedTopic, 2, 42L, userCreated, actions.update(_ :+ "commit"))
          .attempt
      observed <- actions.get
    yield expect.all(result == Left(failure), observed == Vector("user"))
  }

  test("commit failure propagates after persistence, allowing replay on restart") {
    val failure = new RuntimeException("Commit unavailable")

    for
      actions <- Ref.of[IO, Vector[String]](Vector.empty)
      worker   = new PlaybackEventWorker(config, new RecordingRepository(actions))
      result  <- worker
                  .process(
                    config.lessonPublishedTopic,
                    2,
                    42L,
                    lessonPublished,
                    actions.update(_ :+ "commit") *> IO.raiseError(failure),
                  )
                  .attempt
      observed <- actions.get
    yield expect.all(result == Left(failure), observed == Vector("lesson", "commit"))
  }

  private class RecordingRepository(actions: Ref[IO, Vector[String]])
      extends PlaybackProjectionRepository:

    override def userCreated(event: UserCreated): IO[Unit] = actions.update(_ :+ "user")

    override def lessonPublished(event: LessonPublished): IO[Unit] = actions.update(_ :+ "lesson")
