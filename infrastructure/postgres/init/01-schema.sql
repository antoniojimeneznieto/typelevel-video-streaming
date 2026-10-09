-- The outbox/CDC section markers below are required by scripts/setup-*-outbox.sh.
\getenv identity_password IDENTITY_POSTGRES_PASSWORD

CREATE ROLE identity LOGIN PASSWORD :'identity_password';
CREATE DATABASE identity OWNER identity;

\connect identity

SET ROLE identity;

CREATE TABLE users (
  id            UUID        PRIMARY KEY,
  email         TEXT        NOT NULL,
  password_hash TEXT        NOT NULL,
  display_name  TEXT        NOT NULL,
  role          TEXT        NOT NULL CHECK (role IN ('student', 'admin')),
  status        TEXT        NOT NULL CHECK (status IN ('active', 'disabled')),
  created_at    TIMESTAMPTZ NOT NULL,
  updated_at    TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX users_email_unique ON users (lower(email));

-- Identity outbox
CREATE TABLE IF NOT EXISTS outbox (
  id            UUID  PRIMARY KEY,
  aggregatetype TEXT  NOT NULL,
  aggregateid   TEXT  NOT NULL,
  type          TEXT  NOT NULL,
  payload       JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object')
);
-- End Identity outbox

-- Identity CDC
RESET ROLE;

\getenv identity_debezium_password IDENTITY_DEBEZIUM_PASSWORD

SELECT 'CREATE ROLE identity_debezium'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'identity_debezium')
\gexec

ALTER ROLE identity_debezium WITH
  LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS
  PASSWORD :'identity_debezium_password';

GRANT CONNECT ON DATABASE identity TO identity_debezium;
GRANT USAGE ON SCHEMA public TO identity_debezium;
REVOKE ALL ON TABLE public.users FROM identity_debezium;
REVOKE ALL ON TABLE public.outbox FROM identity_debezium;
GRANT SELECT ON TABLE public.outbox TO identity_debezium;

SELECT 'CREATE PUBLICATION identity_outbox_publication FOR TABLE public.outbox WITH (publish = ''insert'')'
WHERE NOT EXISTS (
  SELECT 1 FROM pg_publication WHERE pubname = 'identity_outbox_publication'
)
\gexec

ALTER PUBLICATION identity_outbox_publication SET TABLE public.outbox;
ALTER PUBLICATION identity_outbox_publication SET (publish = 'insert');
-- End Identity CDC

\connect postgres

\getenv catalog_password CATALOG_POSTGRES_PASSWORD

CREATE ROLE catalog LOGIN PASSWORD :'catalog_password';
CREATE DATABASE catalog OWNER catalog;

\connect catalog

BEGIN;

SET ROLE catalog;

CREATE TABLE courses (
  id               UUID    PRIMARY KEY,
  slug             TEXT    NOT NULL UNIQUE,
  title            TEXT    NOT NULL,
  description      TEXT    NOT NULL,
  level            TEXT    NOT NULL CHECK (level IN ('beginner', 'intermediate', 'advanced')),
  kind             TEXT    NOT NULL CHECK (kind IN ('course', 'workshop', 'talk')),
  topic            TEXT    NOT NULL,
  instructor_name  TEXT    NOT NULL,
  instructor_role  TEXT,
  duration_seconds INTEGER CHECK (duration_seconds > 0),
  lesson_count     INTEGER CHECK (lesson_count > 0)
);

CREATE TABLE course_technologies (
  course_id  UUID    NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
  technology TEXT    NOT NULL,
  position   INTEGER NOT NULL CHECK (position >= 0),
  PRIMARY KEY (course_id, technology),
  UNIQUE (course_id, position)
);

CREATE TABLE learning_paths (
  id          TEXT PRIMARY KEY,
  title       TEXT NOT NULL,
  description TEXT NOT NULL,
  time_label  TEXT NOT NULL,
  level       TEXT NOT NULL CHECK (level IN ('beginner', 'intermediate', 'advanced')),
  tone        TEXT NOT NULL CHECK (tone IN ('yellow', 'purple', 'coral'))
);

CREATE TABLE learning_path_courses (
  learning_path_id TEXT    NOT NULL REFERENCES learning_paths(id) ON DELETE CASCADE,
  course_id        UUID    NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
  position         INTEGER NOT NULL CHECK (position > 0),
  PRIMARY KEY (learning_path_id, course_id),
  UNIQUE (learning_path_id, position)
);

CREATE TABLE course_videos (
  course_id       UUID    NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
  lesson_id       TEXT    NOT NULL,
  position        INTEGER NOT NULL CHECK (position > 0),
  title           TEXT    NOT NULL,
  duration_seconds INTEGER NOT NULL CHECK (duration_seconds > 0),
  is_preview      BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (course_id, lesson_id),
  UNIQUE (course_id, position)
);

-- Catalog outbox
CREATE TABLE IF NOT EXISTS outbox (
  id            UUID  PRIMARY KEY,
  aggregatetype TEXT  NOT NULL,
  aggregateid   TEXT  NOT NULL,
  type          TEXT  NOT NULL,
  payload       JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
  UNIQUE (aggregatetype, aggregateid, type)
);
-- End Catalog outbox

COMMIT;

-- Catalog CDC
RESET ROLE;

\getenv catalog_debezium_password CATALOG_DEBEZIUM_PASSWORD

SELECT 'CREATE ROLE catalog_debezium'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'catalog_debezium')
\gexec

ALTER ROLE catalog_debezium WITH
  LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS
  PASSWORD :'catalog_debezium_password';

GRANT CONNECT ON DATABASE catalog TO catalog_debezium;
GRANT USAGE ON SCHEMA public TO catalog_debezium;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM catalog_debezium;
GRANT SELECT ON TABLE public.outbox TO catalog_debezium;

SELECT 'CREATE PUBLICATION catalog_outbox_publication FOR TABLE public.outbox WITH (publish = ''insert'')'
WHERE NOT EXISTS (
  SELECT 1 FROM pg_publication WHERE pubname = 'catalog_outbox_publication'
)
\gexec

ALTER PUBLICATION catalog_outbox_publication SET TABLE public.outbox;
ALTER PUBLICATION catalog_outbox_publication SET (publish = 'insert');
-- End Catalog CDC

\connect postgres

\getenv playback_password PLAYBACK_POSTGRES_PASSWORD

CREATE ROLE playback LOGIN PASSWORD :'playback_password';
CREATE DATABASE playback OWNER playback;

-- Playback projections
\connect playback

SET ROLE playback;

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

RESET ROLE;

\connect catalog

BEGIN;

SET ROLE catalog;

INSERT INTO courses (
  id,
  slug,
  title,
  description,
  level,
  kind,
  topic,
  instructor_name,
  instructor_role,
  duration_seconds,
  lesson_count
) VALUES
  (
    '00000000-0000-0000-0000-000000000101',
    'typelevel-retrospective',
    'A Typelevel Retrospective',
    'Arman Bilge looks back at the people and projects that shaped the modern Typelevel ecosystem, covering cross-platform libraries, the evolution of the Cats Effect runtime, and tools making functional Scala more approachable.',
    'beginner',
    'talk',
    'Typelevel Community',
    'Arman Bilge',
    'Typelevel · Scala Days 2025',
    300,
    1
  ),
  (
    '00000000-0000-0000-0000-000000000102',
    'cats-effect-3',
    'Cats Effect 3',
    'Daniel Spiewak presents Cats Effect 3 and its role in effectful programming with Scala.',
    'intermediate',
    'talk',
    'Effects & Concurrency',
    'Daniel Spiewak',
    'Speaker',
    300,
    1
  ),
  (
    '00000000-0000-0000-0000-000000000104',
    'threads-at-scale',
    'Threads at Scale',
    'Daniel Spiewak connects hardware execution constraints to JVM threads, asynchronous I/O, and high-level effect systems, developing a practical model for improving tail latency in I/O-bound services.',
    'intermediate',
    'talk',
    'Effects & Concurrency',
    'Daniel Spiewak',
    'Speaker · Sphere.it Conf 2022',
    300,
    1
  ),
  (
    '00000000-0000-0000-0000-000000000105',
    'fs2-chunk',
    'fs2.Chunk',
    'Michael Pilquist explores the design of fs2.Chunk and the constraints that shaped its evolution as a building block for functional streaming.',
    'intermediate',
    'talk',
    'Streaming',
    'Michael Pilquist',
    'Speaker',
    300,
    1
  ),
  (
    '00000000-0000-0000-0000-000000000106',
    'rethinking-monad-transformers',
    'Rethinking Monad Transformers',
    'Thanh Le revisits monad transformers and presents a submarine approach to error handling in Scala.',
    'intermediate',
    'talk',
    'Error Handling',
    'Thanh Le',
    'Speaker · Scala Days 2025',
    300,
    1
  );

INSERT INTO course_technologies (course_id, technology, position)
SELECT course.id, technology.name, technology.position
FROM (
  VALUES
    ('typelevel-retrospective', 'Typelevel', 1),
    ('typelevel-retrospective', 'Scala', 2),
    ('typelevel-retrospective', 'Cats Effect', 3),
    ('cats-effect-3', 'Scala', 1),
    ('cats-effect-3', 'Cats Effect', 2),
    ('cats-effect-3', 'Concurrency', 3),
    ('threads-at-scale', 'JVM', 1),
    ('threads-at-scale', 'Concurrency', 2),
    ('threads-at-scale', 'Cats Effect', 3),
    ('fs2-chunk', 'Scala', 1),
    ('fs2-chunk', 'FS2', 2),
    ('rethinking-monad-transformers', 'Scala', 1),
    ('rethinking-monad-transformers', 'Error Handling', 2)
) AS technology(course_slug, name, position)
JOIN courses course ON course.slug = technology.course_slug;

INSERT INTO learning_paths (id, title, description, time_label, level, tone) VALUES
  (
    'discover-typelevel',
    'Discover Typelevel',
    'Explore the ecosystem, its community, and its core ideas.',
    '1 hour 15 minutes',
    'beginner',
    'yellow'
  ),
  (
    'inside-typelevel',
    'Inside Typelevel',
    'Explore the design decisions behind effects, concurrency, streaming, and error handling.',
    '1 hour 52 minutes',
    'intermediate',
    'purple'
  );

INSERT INTO learning_path_courses (learning_path_id, course_id, position)
SELECT membership.path_id, course.id, membership.position
FROM (
  VALUES
    ('discover-typelevel', 'typelevel-retrospective', 1),
    ('discover-typelevel', 'cats-effect-3', 2),
    ('inside-typelevel', 'threads-at-scale', 1),
    ('inside-typelevel', 'fs2-chunk', 2),
    ('inside-typelevel', 'rethinking-monad-transformers', 3)
) AS membership(path_id, course_slug, position)
JOIN courses course ON course.slug = membership.course_slug;

INSERT INTO course_videos (
  course_id,
  lesson_id,
  position,
  title,
  duration_seconds,
  is_preview
)
SELECT
  course.id,
  video.lesson_id,
  video.position,
  video.title,
  video.duration_seconds,
  video.is_preview
FROM (
  VALUES
    ('threads-at-scale', 'lesson-1', 1, 'Threads at Scale', 300, TRUE),
    ('typelevel-retrospective', 'lesson-1', 1, 'A Typelevel Retrospective', 300, TRUE),
    ('fs2-chunk', 'lesson-1', 1, 'fs2.Chunk', 300, TRUE),
    ('cats-effect-3', 'lesson-1', 1, 'Cats Effect 3', 300, TRUE),
    ('rethinking-monad-transformers', 'lesson-1', 1, 'Rethinking Monad Transformers', 300, TRUE)
) AS video(course_slug, lesson_id, position, title, duration_seconds, is_preview)
JOIN courses course ON course.slug = video.course_slug;

-- Catalog outbox seed
WITH lesson_events AS MATERIALIZED (
  SELECT gen_random_uuid() AS event_id, video.*
  FROM course_videos video
)
INSERT INTO outbox (id, aggregatetype, aggregateid, type, payload)
SELECT
  event_id,
  'lesson',
  course_id::text || '/' || lesson_id,
  'LessonPublished',
  jsonb_build_object(
    'eventId', event_id::text,
    'occurredAt', to_char(
      transaction_timestamp() AT TIME ZONE 'UTC',
      'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'
    ),
    'courseId', course_id::text,
    'lessonId', lesson_id,
    'title', title,
    'durationSeconds', duration_seconds,
    'isPreview', is_preview,
    'objectKey', 'courses/' || course_id::text || '/' || lesson_id || '.mp4'
  )
FROM lesson_events
ON CONFLICT (aggregatetype, aggregateid, type) DO NOTHING;
-- End Catalog outbox seed

COMMIT;

RESET ROLE;
