package org.typelevel.video.streaming.backend.user.repository

import java.time.ZoneOffset
import java.util.UUID

import cats.effect.IO
import org.typelevel.video.streaming.backend.user.Profile
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*
import smithy4s.time.Timestamp
import UserProfileRepository.*

final class UserProfileRepository:

  def findById(session: Session[IO], id: UUID): IO[Option[Profile]] =
    session.prepare(findByIdQ).flatMap(_.option(id))

  def findByKeycloakId(session: Session[IO], keycloakId: String): IO[Option[Profile]] =
    session.prepare(findByKeycloakIdQ).flatMap(_.option(keycloakId))

  def insert(
      session: Session[IO],
      keycloakId: String,
      username: String,
      email: Option[String]
  ): IO[Profile] =
    session.prepare(insertQ).flatMap(_.unique((keycloakId, username, email)))

object UserProfileRepository:

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Codecs
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private val timestamp: Codec[Timestamp] =
    timestamptz.imap(odt => Timestamp.fromInstant(odt.toInstant))(t =>
      t.toInstant.atOffset(ZoneOffset.UTC)
    )

  private val profile: Decoder[Profile] =
    (uuid *: text *: timestamp *: text.opt).to[Profile]

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Queries
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private val findByIdQ: Query[UUID, Profile] =
    sql"""
      SELECT id, username, created_at, email
      FROM user_profile
      WHERE id = $uuid
    """.query(profile)

  private val findByKeycloakIdQ: Query[String, Profile] =
    sql"""
      SELECT id, username, created_at, email
      FROM user_profile
      WHERE keycloak_id = $text
    """.query(profile)

  private val insertQ: Query[(String, String, Option[String]), Profile] =
    sql"""
      INSERT INTO user_profile (keycloak_id, username, email)
      VALUES ($text, $text, ${text.opt})
      ON CONFLICT (keycloak_id) DO UPDATE SET username = user_profile.username, email = user_profile.email
      RETURNING id, username, created_at, email
    """.query(profile)
