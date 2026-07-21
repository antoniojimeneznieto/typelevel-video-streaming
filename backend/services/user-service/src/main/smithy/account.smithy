$version: "2"

namespace org.typelevel.video.streaming.backend.user

use alloy#UUID
use alloy#simpleRestJson

@simpleRestJson
@httpBearerAuth
service AccountService {
    version: "1.0.0"
    operations: [GetProfile]
}

@readonly
@http(method: "GET", uri: "/api/account/profile", code: 200)
operation GetProfile {
    output: Profile
}

structure Profile {
    @required
    id: UUID

    @required
    username: String

    email: String

    @required
    @timestampFormat("date-time")
    createdAt: Timestamp
}
