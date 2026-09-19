$version: "2"

namespace org.typelevel.video.streaming.backend.identity.api

use alloy#simpleRestJson
use org.typelevel.video.streaming.backend.identity.domain#DisplayName
use org.typelevel.video.streaming.backend.identity.domain#Email
use org.typelevel.video.streaming.backend.identity.domain#NewPassword
use org.typelevel.video.streaming.backend.identity.domain#Password
use org.typelevel.video.streaming.backend.identity.domain#Role
use org.typelevel.video.streaming.backend.identity.domain#UserId

@simpleRestJson
@httpBearerAuth
service IdentityService {
    version: "1.0.0"
    operations: [Register, Login, GetCurrentUser]
}

@auth([])
@http(method: "POST", uri: "/users", code: 201)
operation Register {
    input: RegisterInput
    output: UserResponse
    errors: [ConflictError]
}

structure RegisterInput {
    @required
    email: Email

    @required
    password: NewPassword

    @required
    displayName: DisplayName
}

@auth([])
@http(method: "POST", uri: "/auth/login", code: 200)
operation Login {
    input: LoginInput
    output: LoginResponse
    errors: [InvalidCredentialsError]
}

structure LoginInput {
    @required
    email: Email

    @required
    password: Password
}

@readonly
@http(method: "GET", uri: "/users/me", code: 200)
operation GetCurrentUser {
    output: UserResponse
}

structure UserResponse {
    @required
    id: UserId

    @required
    email: Email

    @required
    displayName: DisplayName

    @required
    role: Role
}

@sensitive
@length(min: 1)
string AccessToken

enum TokenType {
    BEARER = "Bearer"
}

@range(min: 1)
integer ExpiresInSeconds

structure LoginResponse {
    @required
    accessToken: AccessToken

    @required
    tokenType: TokenType

    @required
    expiresIn: ExpiresInSeconds
}

enum ConflictErrorCode {
    EMAIL_ALREADY_EXISTS = "EMAIL_ALREADY_EXISTS"
}

enum AuthenticationErrorCode {
    INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
}

@error("client")
@httpError(409)
structure ConflictError {
    @required
    code: ConflictErrorCode
}

@error("client")
@httpError(401)
structure InvalidCredentialsError {
    @required
    code: AuthenticationErrorCode
}
