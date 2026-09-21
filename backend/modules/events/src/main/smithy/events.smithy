$version: "2"

metadata smithy4sRenderValidatedNewtypes = true

namespace org.typelevel.video.streaming.backend.events

use alloy#uuidFormat

@uuidFormat
string EventId

@uuidFormat
string UserId

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


structure UserCreated {
    @required
    eventId: EventId

    @required
    @timestampFormat("date-time")
    occurredAt: Timestamp

    @required
    userId: UserId
}

structure LessonPublished {
    @required
    eventId: EventId

    @required
    @timestampFormat("date-time")
    occurredAt: Timestamp

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
