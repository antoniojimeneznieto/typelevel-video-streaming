$version: "2"

namespace org.typelevel.video.streaming.backend.playback.api

use alloy#simpleRestJson
use org.typelevel.video.streaming.backend.playback.domain#CourseId
use org.typelevel.video.streaming.backend.playback.domain#ExpiresInSeconds
use org.typelevel.video.streaming.backend.playback.domain#Favorite
use org.typelevel.video.streaming.backend.playback.domain#FavoritePage
use org.typelevel.video.streaming.backend.playback.domain#LessonId
use org.typelevel.video.streaming.backend.playback.domain#PageLimit
use org.typelevel.video.streaming.backend.playback.domain#PageOffset
use org.typelevel.video.streaming.backend.playback.domain#PlaybackProgress
use org.typelevel.video.streaming.backend.playback.domain#PlaybackProgressPage
use org.typelevel.video.streaming.backend.playback.domain#PlaybackUrl
use org.typelevel.video.streaming.backend.playback.domain#PositionSeconds

@simpleRestJson
@httpBearerAuth
service PlaybackService {
    version: "1.0.0"
    operations: [
        GetPlaybackUrl
        UpdatePlaybackProgress
        ListPlaybackProgress
        AddFavorite
        RemoveFavorite
        ListFavorites
    ]
}

@readonly
@http(method: "GET", uri: "/courses/{courseId}/lessons/{lessonId}/playback", code: 200)
operation GetPlaybackUrl {
    input: GetPlaybackUrlInput
    output: PlaybackUrlResponse
    errors: [VideoNotFoundError, PlaybackUnavailableError]
}

structure GetPlaybackUrlInput {
    @required
    @httpLabel
    courseId: CourseId

    @required
    @httpLabel
    lessonId: LessonId
}

structure PlaybackUrlResponse {
    @required
    url: PlaybackUrl

    @required
    expiresIn: ExpiresInSeconds
}

@idempotent
@http(method: "PUT", uri: "/courses/{courseId}/lessons/{lessonId}/progress", code: 200)
operation UpdatePlaybackProgress {
    input: UpdatePlaybackProgressInput
    output: PlaybackProgress
    errors: [VideoNotFoundError, InvalidPlaybackProgressError, PlaybackUnavailableError]
}

structure UpdatePlaybackProgressInput {
    @required
    @httpLabel
    courseId: CourseId

    @required
    @httpLabel
    lessonId: LessonId

    @required
    positionSeconds: PositionSeconds
}

@readonly
@http(method: "GET", uri: "/progress", code: 200)
operation ListPlaybackProgress {
    input: ListPlaybackProgressInput
    output: PlaybackProgressPage
    errors: [PlaybackUnavailableError]
}

structure ListPlaybackProgressInput {
    @httpQuery("courseId")
    courseId: CourseId

    @httpQuery("completed")
    completed: Boolean

    @default(20)
    @httpQuery("limit")
    limit: PageLimit

    @default(0)
    @httpQuery("offset")
    offset: PageOffset
}

@idempotent
@http(method: "PUT", uri: "/favorites/{courseId}", code: 200)
operation AddFavorite {
    input: AddFavoriteInput
    output: Favorite
    errors: [CourseNotFoundError, PlaybackUnavailableError]
}

structure AddFavoriteInput {
    @required
    @httpLabel
    courseId: CourseId
}

@idempotent
@http(method: "DELETE", uri: "/favorites/{courseId}", code: 204)
operation RemoveFavorite {
    input: RemoveFavoriteInput
    errors: [PlaybackUnavailableError]
}

structure RemoveFavoriteInput {
    @required
    @httpLabel
    courseId: CourseId
}

@readonly
@http(method: "GET", uri: "/favorites", code: 200)
operation ListFavorites {
    input: ListFavoritesInput
    output: FavoritePage
    errors: [PlaybackUnavailableError]
}

structure ListFavoritesInput {
    @default(20)
    @httpQuery("limit")
    limit: PageLimit

    @default(0)
    @httpQuery("offset")
    offset: PageOffset
}

@error("client")
@httpError(400)
structure InvalidPlaybackProgressError {
    @required
    message: String
}

@error("client")
@httpError(404)
structure CourseNotFoundError {
    @required
    message: String
}

@error("client")
@httpError(404)
structure VideoNotFoundError {
    @required
    message: String
}

@error("server")
@httpError(503)
structure PlaybackUnavailableError {
    @required
    message: String
}
