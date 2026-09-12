$version: "2"

metadata smithy4sRenderValidatedNewtypes = true

namespace org.typelevel.video.streaming.backend.identity.domain

use alloy#uuidFormat

@uuidFormat
string UserId

@sensitive
@length(min: 3, max: 254)
@pattern("^[^@\\s]+@[^@\\s]+$")
string Email

@sensitive
@length(min: 1, max: 128)
string Password

@sensitive
@length(min: 12, max: 128)
string NewPassword

@sensitive
@length(min: 1, max: 512)
@pattern(".*\\S.*")
string PasswordHash

@sensitive
@length(min: 1, max: 100)
@pattern(".*\\S.*")
string DisplayName

enum Role {
    STUDENT = "student"
    ADMIN = "admin"
}

enum UserStatus {
    ACTIVE = "active"
    DISABLED = "disabled"
}

enum AuthenticationFailure {
    USER_NOT_FOUND = "USER_NOT_FOUND"
    INVALID_PASSWORD = "INVALID_PASSWORD"
    USER_DISABLED = "USER_DISABLED"
    INVALID_STORED_HASH = "INVALID_STORED_HASH"
}

structure User {
    @required
    id: UserId

    @required
    email: Email

    @required
    passwordHash: PasswordHash

    @required
    displayName: DisplayName

    @required
    role: Role

    @required
    status: UserStatus

    @required
    @timestampFormat("date-time")
    createdAt: Timestamp

    @required
    @timestampFormat("date-time")
    updatedAt: Timestamp
}
