package org.typelevel.video.streaming.backend.identity.service

import java.util.{Locale, UUID}

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import org.typelevel.video.streaming.backend.identity.api.*
import org.typelevel.video.streaming.backend.identity.domain.*
import org.typelevel.video.streaming.backend.identity.repository.*
import org.typelevel.video.streaming.backend.runtime.context.RequestContext
import smithy4s.time.Timestamp

final class IdentityServiceImpl(
    repository: IdentityRepository,
    passwordHasher: PasswordHasher,
    accessTokenIssuer: AccessTokenIssuer,
    requestContext: RequestContext[IO, UUID],
) extends IdentityService[IO]:

  override def register(
      email: Email,
      password: NewPassword,
      displayName: DisplayName,
  ): IO[UserResponse] =
    for
      normalizedEmail <- normalize(email)
      passwordHash    <- passwordHasher.hash(password)
      id              <- IO(UUID.randomUUID()).map(UserId(_))
      now             <- Clock[IO].realTimeInstant.map(Timestamp.fromInstant)
      user             = User(
               id           = id,
               email        = normalizedEmail,
               passwordHash = passwordHash,
               displayName  = displayName,
               role         = Role.STUDENT,
               status       = UserStatus.ACTIVE,
               createdAt    = now,
               updatedAt    = now,
             )
      created  <- repository.create(user)
      response <- created match
                    case Some(createdUser) => IO.pure(toResponse(createdUser))
                    case None =>
                      IO.raiseError(ConflictError(ConflictErrorCode.EMAIL_ALREADY_EXISTS))
    yield response

  override def login(email: Email, password: Password): IO[LoginResponse] =
    for
      normalizedEmail <- normalize(email)
      maybeUser       <- repository.findByEmail(normalizedEmail)
      user            <- maybeUser match
                case Some(user) => IO.pure(user)
                case None => IO.raiseError(invalidCredentials)
      passwordMatches <- passwordHasher.verify(password, user.passwordHash)
      _               <- IO.raiseUnless(passwordMatches && user.status == UserStatus.ACTIVE)(
             invalidCredentials,
           )
      issued <- accessTokenIssuer.issue(user.id, user.role, user.email)
    yield LoginResponse(
      accessToken = issued.accessToken,
      tokenType   = TokenType.BEARER,
      expiresIn   = issued.expiresIn,
    )

  override def getCurrentUser(): IO[UserResponse] =
    for
      userId <- requestContext.get.flatMap {
                  case Some(userId) => IO.pure(userId)
                  case None =>
                    IO.raiseError(
                      new IllegalStateException("Authenticated request context is missing"),
                    )
                }
      user <- repository.findById(UserId(userId)).flatMap {
                case Some(user) => IO.pure(user)
                case None =>
                  IO.raiseError(new IllegalStateException("Authenticated user does not exist"))
              }
    yield toResponse(user)

  private def normalize(email: Email): IO[Email] =
    Email(Email.value(email).toLowerCase(Locale.ROOT))
      .leftMap(new IllegalStateException(_))
      .liftTo[IO]

  private def toResponse(user: User): UserResponse =
    UserResponse(
      id          = user.id,
      email       = user.email,
      displayName = user.displayName,
      role        = user.role,
    )

  private def invalidCredentials: InvalidCredentialsError =
    InvalidCredentialsError(AuthenticationErrorCode.INVALID_CREDENTIALS)
