$version: "2"

namespace org.typelevel.video.streaming.backend.identity.auth

use alloy#uuidFormat
use org.typelevel.video.streaming.backend.identity.domain#Role
use org.typelevel.video.streaming.backend.identity.domain#UserId

enum TokenIssuer {
    IDENTITY = "identity"
}

enum TokenAudience {
    COURSE_PLATFORM = "course-platform"
}

@uuidFormat
string JwtId

@range(min: 0)
long JwtNumericDate

structure AccessTokenClaims {
    @required
    iss: TokenIssuer

    @required
    sub: UserId

    @required
    aud: TokenAudience

    @required
    role: Role

    @required
    iat: JwtNumericDate

    @required
    exp: JwtNumericDate

    @required
    jti: JwtId
}
