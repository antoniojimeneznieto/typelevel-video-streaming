$version: "2"

metadata smithy4sRenderValidatedNewtypes = true

namespace org.typelevel.video.streaming.backend.catalog.domain

use alloy#uuidFormat

@uuidFormat
string CourseId

@length(min: 1, max: 100)
@pattern("^[a-z0-9]+(-[a-z0-9]+)*$")
string CourseSlug

@length(min: 1, max: 200)
@pattern(".*\\S.*")
string CourseTitle

@length(min: 1, max: 4000)
@pattern(".*\\S.*")
string CourseDescription

@length(min: 1, max: 100)
@pattern(".*\\S.*")
string Topic

@length(min: 1, max: 100)
@pattern(".*\\S.*")
string Technology

@length(min: 1, max: 100)
@pattern(".*\\S.*")
string InstructorName

@length(min: 1, max: 100)
@pattern(".*\\S.*")
string InstructorRole

@range(min: 1)
integer DurationSeconds

@range(min: 1)
integer LessonCount

enum CourseLevel {
    BEGINNER = "beginner"
    INTERMEDIATE = "intermediate"
    ADVANCED = "advanced"
}

enum CourseKind {
    COURSE = "course"
    WORKSHOP = "workshop"
    TALK = "talk"
}

list TechnologyList {
    member: Technology
}

structure Instructor {
    @required
    name: InstructorName

    role: InstructorRole
}

structure Course {
    @required
    id: CourseId

    @required
    slug: CourseSlug

    @required
    title: CourseTitle

    @required
    description: CourseDescription

    @required
    level: CourseLevel

    @required
    kind: CourseKind

    @required
    topic: Topic

    @required
    technologies: TechnologyList

    @required
    instructor: Instructor

    durationSeconds: DurationSeconds

    lessonCount: LessonCount
}

list CourseList {
    member: Course
}

@length(min: 1, max: 100)
@pattern("^[a-z0-9]+(-[a-z0-9]+)*$")
string LearningPathId

@length(min: 1, max: 200)
@pattern(".*\\S.*")
string LearningPathTitle

@length(min: 1, max: 4000)
@pattern(".*\\S.*")
string LearningPathDescription

@length(min: 1, max: 40)
@pattern(".*\\S.*")
string TimeLabel

enum LearningPathTone {
    YELLOW = "yellow"
    PURPLE = "purple"
    CORAL = "coral"
}

list CourseIdList {
    member: CourseId
}

structure LearningPath {
    @required
    id: LearningPathId

    @required
    title: LearningPathTitle

    @required
    description: LearningPathDescription

    @required
    timeLabel: TimeLabel

    @required
    level: CourseLevel

    @required
    tone: LearningPathTone

    @required
    courseIds: CourseIdList
}

list LearningPathList {
    member: LearningPath
}

@length(min: 1, max: 100)
@pattern(".*\\S.*")
string SearchQuery

@range(min: 1, max: 100)
integer PageLimit

@range(min: 0)
integer PageOffset

@range(min: 0)
long TotalCount

structure CourseFilter {
    query: SearchQuery
    level: CourseLevel
    kind: CourseKind
    topic: Topic
    technology: Technology

    @required
    limit: PageLimit

    @required
    offset: PageOffset
}

structure LearningPathFilter {
    query: SearchQuery
    level: CourseLevel
    tone: LearningPathTone

    @required
    limit: PageLimit

    @required
    offset: PageOffset
}

structure CoursePage {
    @required
    items: CourseList

    @required
    total: TotalCount

    @required
    limit: PageLimit

    @required
    offset: PageOffset
}

structure LearningPathPage {
    @required
    items: LearningPathList

    @required
    total: TotalCount

    @required
    limit: PageLimit

    @required
    offset: PageOffset
}
