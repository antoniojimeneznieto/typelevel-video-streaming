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

INSERT INTO courses (
  id, slug, title, description, level, kind, topic,
  instructor_name, instructor_role, duration_seconds, lesson_count
) VALUES
  (
    '00000000-0000-0000-0000-000000000003', 'scala-basics', 'Scala Basics',
    'Getting started with Scala.', 'beginner', 'course', 'Language',
    'Alex', NULL, NULL, NULL
  ),
  (
    '00000000-0000-0000-0000-000000000002', 'fs2-streaming', 'FS2 Streaming',
    'Functional streams and chunks.', 'intermediate', 'talk', 'Streaming',
    'Michael Pilquist', NULL, 2874, 1
  ),
  (
    '00000000-0000-0000-0000-000000000001', 'cats-effect', 'Cats Effect 3',
    'Practical concurrency with fibers.', 'intermediate', 'talk', 'Effects & Concurrency',
    'Daniel Spiewak', 'Speaker', 2370, 1
  );

INSERT INTO course_technologies (course_id, technology, position) VALUES
  ('00000000-0000-0000-0000-000000000001', 'Cats Effect', 2),
  ('00000000-0000-0000-0000-000000000001', 'Scala', 1),
  ('00000000-0000-0000-0000-000000000002', 'FS2', 1);

INSERT INTO learning_paths (id, title, description, time_label, level, tone) VALUES
  (
    'inside-typelevel', 'Inside Typelevel', 'Explore effects and streaming.',
    '1 hour', 'intermediate', 'purple'
  ),
  (
    'discover-typelevel', 'Discover Typelevel', 'Explore the Typelevel ecosystem.',
    '2 hours', 'beginner', 'yellow'
  );

INSERT INTO learning_path_courses (learning_path_id, course_id, position) VALUES
  ('discover-typelevel', '00000000-0000-0000-0000-000000000001', 2),
  ('discover-typelevel', '00000000-0000-0000-0000-000000000002', 1);
