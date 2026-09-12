$version: "2"

namespace org.typelevel.video.streaming.backend.catalog.api

use alloy#simpleRestJson
use org.typelevel.video.streaming.backend.catalog.domain#CourseKind
use org.typelevel.video.streaming.backend.catalog.domain#CourseLevel
use org.typelevel.video.streaming.backend.catalog.domain#CoursePage
use org.typelevel.video.streaming.backend.catalog.domain#LearningPathPage
use org.typelevel.video.streaming.backend.catalog.domain#LearningPathTone
use org.typelevel.video.streaming.backend.catalog.domain#PageLimit
use org.typelevel.video.streaming.backend.catalog.domain#PageOffset
use org.typelevel.video.streaming.backend.catalog.domain#SearchQuery
use org.typelevel.video.streaming.backend.catalog.domain#Technology
use org.typelevel.video.streaming.backend.catalog.domain#Topic

@simpleRestJson
service CatalogService {
    version: "1.0.0"
    operations: [ListCourses, ListLearningPaths]
}

@readonly
@http(method: "GET", uri: "/courses", code: 200)
operation ListCourses {
    input: ListCoursesInput
    output: CoursePage
}

structure ListCoursesInput {
    @httpQuery("q")
    query: SearchQuery

    @httpQuery("level")
    level: CourseLevel

    @httpQuery("kind")
    kind: CourseKind

    @httpQuery("topic")
    topic: Topic

    @httpQuery("technology")
    technology: Technology

    @default(20)
    @httpQuery("limit")
    limit: PageLimit

    @default(0)
    @httpQuery("offset")
    offset: PageOffset
}

@readonly
@http(method: "GET", uri: "/learning-paths", code: 200)
operation ListLearningPaths {
    input: ListLearningPathsInput
    output: LearningPathPage
}

structure ListLearningPathsInput {
    @httpQuery("q")
    query: SearchQuery

    @httpQuery("level")
    level: CourseLevel

    @httpQuery("tone")
    tone: LearningPathTone

    @default(20)
    @httpQuery("limit")
    limit: PageLimit

    @default(0)
    @httpQuery("offset")
    offset: PageOffset
}
