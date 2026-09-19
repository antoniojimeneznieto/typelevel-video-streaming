$version: "2"

metadata smithy4sRenderValidatedNewtypes = true

namespace org.typelevel.video.streaming.backend.playback.domain

use alloy#uuidFormat

@uuidFormat
string CourseId

@length(min: 1, max: 100)
@pattern("^[a-z0-9]+(-[a-z0-9]+)*$")
string LessonId

@length(min: 1, max: 200)
@pattern(".*\\S.*")
string LessonTitle

@range(min: 1)
integer DurationSeconds

@length(min: 1, max: 1024)
@pattern("^[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*\\.mp4$")
string ObjectKey

structure Lesson {
    @required
    courseId: CourseId

    @required
    lessonId: LessonId

    @required
    title: LessonTitle

    @required
    durationSeconds: DurationSeconds

    @required
    isPreview: Boolean

    @required
    objectKey: ObjectKey
}

@sensitive
@length(min: 1)
@pattern("^https?://.+$")
string PlaybackUrl

@range(min: 1)
integer ExpiresInSeconds

@range(min: 0)
integer PositionSeconds

@range(min: 1, max: 100)
integer PageLimit

@range(min: 0)
integer PageOffset

@range(min: 0)
long TotalCount

structure PlaybackProgress {
    @required
    courseId: CourseId

    @required
    lessonId: LessonId

    @required
    positionSeconds: PositionSeconds

    @required
    completed: Boolean

    @required
    @timestampFormat("date-time")
    updatedAt: Timestamp
}

list PlaybackProgressList {
    member: PlaybackProgress
}

structure PlaybackProgressPage {
    @required
    items: PlaybackProgressList

    @required
    total: TotalCount

    @required
    limit: PageLimit

    @required
    offset: PageOffset
}

structure Favorite {
    @required
    courseId: CourseId

    @required
    @timestampFormat("date-time")
    createdAt: Timestamp
}

list FavoriteList {
    member: Favorite
}

structure FavoritePage {
    @required
    items: FavoriteList

    @required
    total: TotalCount

    @required
    limit: PageLimit

    @required
    offset: PageOffset
}
