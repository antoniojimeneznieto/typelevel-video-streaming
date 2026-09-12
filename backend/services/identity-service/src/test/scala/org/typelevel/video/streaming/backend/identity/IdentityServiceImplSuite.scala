package org.typelevel.video.streaming.backend.identity.service

import java.util.UUID

import cats.effect.{IO, Ref}
import org.typelevel.video.streaming.backend.identity.api.*
import org.typelevel.video.streaming.backend.identity.auth.*
import org.typelevel.video.streaming.backend.identity.domain.*
import org.typelevel.video.streaming.backend.identity.repository.*
import org.typelevel.video.streaming.backend.runtime.context.IOLocalRequestContext
import smithy4s.time.Timestamp
import weaver.SimpleIOSuite

object IdentityServiceImplSuite extends SimpleIOSuite:

  private val id              = UserId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"))
  private val now             = Timestamp.fromEpochSecond(1786200000L)
  private val email           = valid(Email("Alice@Example.com"))
  private val normalizedEmail = valid(Email("alice@example.com"))
  private val newPassword     = valid(NewPassword("very-secret-password"))
  private val password        = valid(Password("very-secret-password"))
  private val passwordHash    = valid(PasswordHash("stored-password-hash"))
  private val displayName     = valid(DisplayName("Alice"))
  private val accessToken     = valid(AccessToken("signed.jwt.token"))
  private val expiresIn       = valid(ExpiresInSeconds(1800))
  private val issuedToken     = IssuedAccessToken(accessToken, expiresIn)

  test("registration creates an active student with a normalized email") {
    for
      created   <- Ref.of[IO, Option[User]](None)
      context   <- IOLocalRequestContext.create[AccessTokenClaims]
      repository = repositoryStub(
                     createF = user => created.set(Some(user)).as(Some(user))
                   )
      service   = serviceWith(repository, context)
      response <- service.register(email, newPassword, displayName)
      stored   <- created.get
    yield expect(
      stored.exists { user =>
        user.id == response.id &&
        user.email == normalizedEmail &&
        user.passwordHash == passwordHash &&
        user.displayName == displayName &&
        user.role == Role.STUDENT &&
        user.status == UserStatus.ACTIVE &&
        user.createdAt == user.updatedAt
      }
    ) and expect(response.email == normalizedEmail)
  }

  test("registration maps a duplicate email to the modeled conflict") {
    for
      context   <- IOLocalRequestContext.create[AccessTokenClaims]
      repository = repositoryStub(
                     createF = _ => IO.pure(None)
                   )
      service = serviceWith(repository, context)
      result <- service.register(email, newPassword, displayName).attempt
    yield expect(
      result == Left(ConflictError(ConflictErrorCode.EMAIL_ALREADY_EXISTS))
    )
  }

  test("login verifies an active user and returns the issued bearer token") {
    for
      context   <- IOLocalRequestContext.create[AccessTokenClaims]
      repository = repositoryStub(findByEmailF = _ => IO.pure(Some(activeUser)))
      service    = serviceWith(repository, context)
      response  <- service.login(normalizedEmail, password)
    yield expect(
      response == LoginResponse(accessToken, TokenType.BEARER, expiresIn)
    )
  }

  test("login returns the same error for missing, disabled, and invalid users") {
    for
      context <- IOLocalRequestContext.create[AccessTokenClaims]
      missing <- serviceWith(repositoryStub(), context)
                   .login(normalizedEmail, password)
                   .attempt
      disabled <- serviceWith(
                    repositoryStub(findByEmailF =
                      _ => IO.pure(Some(activeUser.copy(status = UserStatus.DISABLED)))
                    ),
                    context
                  ).login(normalizedEmail, password).attempt
      invalidPassword <- serviceWith(
                           repositoryStub(findByEmailF = _ => IO.pure(Some(activeUser))),
                           context,
                           passwordMatches = false
                         ).login(normalizedEmail, password).attempt
      expected = Left(InvalidCredentialsError(AuthenticationErrorCode.INVALID_CREDENTIALS))
    yield expect(missing == expected) and
      expect(disabled == expected) and
      expect(invalidPassword == expected)
  }

  test("current user is loaded from the authenticated request context") {
    for
      context   <- IOLocalRequestContext.create[AccessTokenClaims]
      repository = repositoryStub(findByIdF = _ => IO.pure(Some(activeUser)))
      service    = serviceWith(repository, context)
      response  <- context.scope(claims)(service.getCurrentUser())
    yield expect(
      response == UserResponse(id, normalizedEmail, displayName, Role.STUDENT)
    )
  }

  private val activeUser = User(
    id           = id,
    email        = normalizedEmail,
    passwordHash = passwordHash,
    displayName  = displayName,
    role         = Role.STUDENT,
    status       = UserStatus.ACTIVE,
    createdAt    = now,
    updatedAt    = now
  )

  private val claims = AccessTokenClaims(
    iss  = TokenIssuer.IDENTITY,
    sub  = id,
    aud  = TokenAudience.COURSE_PLATFORM,
    role = Role.STUDENT,
    iat  = valid(JwtNumericDate(1786200000L)),
    exp  = valid(JwtNumericDate(1786201800L)),
    jti  = JwtId(UUID.fromString("978c2e02-d49f-4c0a-a76d-72448a47e88d"))
  )

  private def serviceWith(
      repository: IdentityRepository,
      context: IOLocalRequestContext[AccessTokenClaims],
      passwordMatches: Boolean = true
  ): IdentityServiceImpl =
    new IdentityServiceImpl(
      repository     = repository,
      passwordHasher = new PasswordHasher:
        override def hash(password: NewPassword): IO[PasswordHash] = IO.pure(passwordHash)
        override def verify(password: Password, hash: PasswordHash): IO[Boolean] =
          IO.pure(passwordMatches)
      ,
      accessTokenIssuer = new AccessTokenIssuer:
        override def issue(userId: UserId, role: Role): IO[IssuedAccessToken] =
          IO.pure(issuedToken)
      ,
      requestContext = context
    )

  private def repositoryStub(
      createF: User => IO[Option[User]]       = user => IO.pure(Some(user)),
      findByIdF: UserId => IO[Option[User]]   = _ => IO.pure(None),
      findByEmailF: Email => IO[Option[User]] = _ => IO.pure(None)
  ): IdentityRepository =
    new IdentityRepository:
      override def create(user: User): IO[Option[User]]        = createF(user)
      override def findById(id: UserId): IO[Option[User]]      = findByIdF(id)
      override def findByEmail(email: Email): IO[Option[User]] = findByEmailF(email)

  private def valid[A](either: Either[String, A]): A =
    either.fold(message => throw new AssertionError(message), identity)
