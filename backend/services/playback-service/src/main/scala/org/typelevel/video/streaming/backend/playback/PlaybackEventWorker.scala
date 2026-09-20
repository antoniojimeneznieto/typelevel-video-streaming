package org.typelevel.video.streaming.backend.playback.worker

import cats.effect.IO
import fs2.kafka.otel4s.trace.KafkaTracer
import fs2.kafka.otel4s.trace.syntax.*
import fs2.kafka.{AutoOffsetReset, CommitRecovery, ConsumerSettings, Deserializer, KafkaConsumer}
import org.typelevel.otel4s.trace.TracerProvider
import org.typelevel.video.streaming.backend.events.{LessonPublished, UserCreated}
import org.typelevel.video.streaming.backend.playback.config.KafkaConfig
import org.typelevel.video.streaming.backend.playback.repository.PlaybackProjectionRepository
import smithy4s.json.Json
import smithy4s.{Blob, Schema}

final class PlaybackEventWorker(
    config: KafkaConfig,
    repository: PlaybackProjectionRepository,
)(using TracerProvider[IO]):

  private val stringDeserializer = Deserializer[IO, String]

  private val settings = ConsumerSettings[IO, String, Option[String]](
    stringDeserializer,
    stringDeserializer.option,
  )
    .withBootstrapServers(config.bootstrapServers)
    .withGroupId(config.groupId)
    .withAutoOffsetReset(AutoOffsetReset.Earliest)
    .withEnableAutoCommit(false)
    .withCommitRecovery(CommitRecovery.None)

  def run: IO[Unit] =
    KafkaConsumer
      .stream(settings)
      .subscribeTo(config.lessonPublishedTopic, config.userCreatedTopic)
      .traced(KafkaTracer.Config.default)
      .recordsWithProcessTraced { committable =>
        val record = committable.record
        process(
          record.topic,
          record.partition,
          record.offset,
          record.value,
          committable.offset.commit,
        )
      }
      .compile
      .drain

  private[playback] def process(
      topic: String,
      partition: Int,
      offset: Long,
      value: Option[String],
      commit: IO[Unit],
  ): IO[Unit] =
    def invalidRecord: IllegalArgumentException =
      new IllegalArgumentException(
        s"Invalid playback event at topic=$topic partition=$partition offset=$offset",
      )

    def decode[A: Schema](value: String): IO[A] =
      IO(Json.read[A](Blob(value))).attempt.flatMap {
        case Right(Right(event)) => IO.pure(event)
        case _ => IO.raiseError(invalidRecord)
      }

    val applyEvent = value match
      case Some(value) if topic == config.lessonPublishedTopic =>
        decode[LessonPublished](value).flatMap(repository.lessonPublished)
      case Some(value) if topic == config.userCreatedTopic =>
        decode[UserCreated](value).flatMap(repository.userCreated)
      case _ => IO.raiseError[Unit](invalidRecord)

    applyEvent *> commit
