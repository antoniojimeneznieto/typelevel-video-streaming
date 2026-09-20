CREATE TABLE users (
  id         UUID        PRIMARY KEY,
  event_id   UUID        NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE lessons (
  course_id        UUID        NOT NULL,
  lesson_id        TEXT        NOT NULL,
  title            TEXT        NOT NULL,
  duration_seconds INTEGER     NOT NULL CHECK (duration_seconds > 0),
  is_preview       BOOLEAN     NOT NULL,
  object_key       TEXT        NOT NULL,
  event_id         UUID        NOT NULL UNIQUE,
  published_at     TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (course_id, lesson_id)
);

CREATE TABLE playback_progress (
  user_id          UUID        NOT NULL REFERENCES users(id),
  course_id        UUID        NOT NULL,
  lesson_id        TEXT        NOT NULL,
  position_seconds INTEGER     NOT NULL CHECK (position_seconds >= 0),
  completed        BOOLEAN     NOT NULL,
  updated_at       TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (user_id, course_id, lesson_id),
  FOREIGN KEY (course_id, lesson_id) REFERENCES lessons(course_id, lesson_id)
);

CREATE INDEX playback_progress_user_updated_at
  ON playback_progress (user_id, updated_at DESC, course_id, lesson_id);

CREATE TABLE favorites (
  user_id    UUID        NOT NULL REFERENCES users(id),
  course_id  UUID        NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (user_id, course_id)
);

CREATE INDEX favorites_user_created_at
  ON favorites (user_id, created_at DESC, course_id);

INSERT INTO users (id, event_id, created_at) VALUES
  (
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000001001', '2026-09-07T12:00:00Z'
  ),
  (
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000001002', '2026-09-07T12:00:00Z'
  );

INSERT INTO lessons (
  course_id, lesson_id, title, duration_seconds, is_preview, object_key, event_id, published_at
) VALUES
  (
    '00000000-0000-0000-0000-000000000104', 'lesson-1', 'Threads at Scale', 300, TRUE,
    'published/talks/threads-at-scale.mp4',
    '00000000-0000-0000-0000-000000002001', '2026-09-07T12:00:00Z'
  ),
  (
    '00000000-0000-0000-0000-000000000104', 'lesson-2', 'Concurrency in Practice', 300, FALSE,
    'published/talks/concurrency-in-practice.mp4',
    '00000000-0000-0000-0000-000000002002', '2026-09-07T12:00:00Z'
  ),
  (
    '00000000-0000-0000-0000-000000000105', 'lesson-1', 'FS2 Streaming', 300, TRUE,
    'published/talks/fs2-streaming.mp4',
    '00000000-0000-0000-0000-000000002003', '2026-09-07T12:00:00Z'
  );
