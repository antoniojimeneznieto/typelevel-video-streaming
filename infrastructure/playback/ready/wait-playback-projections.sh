#!/usr/bin/env bash
set -euo pipefail

readonly TIMEOUT_SECONDS=120 COMMAND_TIMEOUT_SECONDS=10 REQUEST_TIMEOUT_SECONDS=5 POLL_SECONDS=2
deadline=$((SECONDS + TIMEOUT_SECONDS))
last_pending="The event pipeline has not become ready."
fail() { echo "Playback readiness check failed: $*" >&2; exit 1; }
pending() { last_pending=$*; return 1; }

postgres_host=${POSTGRES_HOST:-postgres}
postgres_port=${POSTGRES_PORT:-5432}
connect_url=${DEBEZIUM_CONNECT_URL:-http://debezium-connect:8083}
playback_host=${PLAYBACK_HOST:-playback-service}
playback_port=${PLAYBACK_PORT:-8083}
while [[ "$connect_url" == */ ]]; do connect_url=${connect_url%/}; done
case "$connect_url" in
  http://*|https://*) ;;
  *) fail "DEBEZIUM_CONNECT_URL must use HTTP or HTTPS." ;;
esac
if [[ ! "$playback_port" =~ ^[0-9]{1,5}$ ]] ||
   (( 10#$playback_port < 1 || 10#$playback_port > 65535 )); then
  fail "PLAYBACK_PORT must be a valid TCP port."
fi
playback_port=$((10#$playback_port))

for required_command in psql curl jq timeout; do
  command -v "$required_command" >/dev/null || fail "Missing required command: $required_command"
done
for service in identity catalog playback; do
  password_variable="${service^^}_POSTGRES_PASSWORD"
  [[ -v "$password_variable" ]] || fail "$password_variable must be configured."
done

umask 077
work_directory=$(mktemp -d)
cleanup() {
  rm -f -- "$work_directory/identity.json" "$work_directory/catalog.json" \
    "$work_directory/playback.json" "$work_directory/status.json" "$work_directory/result.json"
  rmdir -- "$work_directory"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

read -r -d '' identity_query <<'SQL' || :
SELECT jsonb_build_object(
  'userIds', (SELECT COALESCE(jsonb_agg(id), '[]'::jsonb) FROM public.users),
  'events', (
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
      'rowId', id, 'aggregateType', aggregatetype, 'aggregateId', aggregateid,
      'payloadType', jsonb_typeof(payload),
      'event', jsonb_build_object(
        'eventId', payload -> 'eventId', 'occurredAt', payload -> 'occurredAt',
        'userId', payload -> 'userId'
      )
    )), '[]'::jsonb)
    FROM public.outbox WHERE type = 'UserCreated'
  )
)
SQL

read -r -d '' catalog_query <<'SQL' || :
SELECT COALESCE(jsonb_agg(jsonb_build_object(
  'rowId', id, 'aggregateType', aggregatetype, 'aggregateId', aggregateid,
  'payloadType', jsonb_typeof(payload),
  'event', jsonb_build_object(
    'eventId', payload -> 'eventId', 'occurredAt', payload -> 'occurredAt',
    'courseId', payload -> 'courseId', 'lessonId', payload -> 'lessonId',
    'title', payload -> 'title', 'durationSeconds', payload -> 'durationSeconds',
    'isPreview', payload -> 'isPreview', 'objectKey', payload -> 'objectKey'
  )
)), '[]'::jsonb)
FROM public.outbox WHERE type = 'LessonPublished'
SQL

read -r -d '' playback_query <<'SQL' || :
SELECT jsonb_build_object(
  'users', (SELECT COALESCE(jsonb_agg(jsonb_build_object(
    'userId', id, 'eventId', event_id, 'occurredAt', created_at
  )), '[]'::jsonb) FROM public.users),
  'lessons', (SELECT COALESCE(jsonb_agg(jsonb_build_object(
    'courseId', course_id, 'lessonId', lesson_id, 'title', title,
    'durationSeconds', duration_seconds, 'isPreview', is_preview,
    'objectKey', object_key, 'eventId', event_id, 'occurredAt', published_at
  )), '[]'::jsonb) FROM public.lessons)
)
SQL

remaining_timeout() {
  operation_timeout=$((deadline - SECONDS))
  (( operation_timeout > 0 )) || { pending "The readiness deadline has elapsed."; return 1; }
  if (( operation_timeout > $1 )); then operation_timeout=$1; fi
}

database_json() {
  local service=$1 query=$2 prefix=${1^^}
  local password_variable="${prefix}_POSTGRES_PASSWORD"
  local user_variable="${prefix}_POSTGRES_USER" database_variable="${prefix}_POSTGRES_DATABASE"
  remaining_timeout "$COMMAND_TIMEOUT_SECONDS" || return 1
  if ! PGPASSWORD="${!password_variable}" \
    PGOPTIONS="-c default_transaction_read_only=on -c statement_timeout=10000" \
    timeout --foreground --signal=KILL "$operation_timeout" \
    psql -X -w -qAt -v ON_ERROR_STOP=1 -h "$postgres_host" -p "$postgres_port" \
      -U "${!user_variable-$service}" -d "${!database_variable-$service}" -c "$query" \
      >"$work_directory/$service.json" 2>/dev/null; then
    pending "The $service database is unavailable; inspect its service logs."
    return 1
  fi
  jq -se 'length == 1' "$work_directory/$service.json" >/dev/null 2>&1 ||
    fail "The $service database returned malformed JSON."
}

check_services() {
  local service http_status state
  for service in identity catalog; do
    remaining_timeout "$REQUEST_TIMEOUT_SECONDS" || return 1
    if ! http_status=$(curl --disable --silent --connect-timeout "$operation_timeout" \
      --max-time "$operation_timeout" --output "$work_directory/status.json" \
      --write-out '%{http_code}' "$connect_url/connectors/$service-outbox/status" 2>/dev/null); then
      http_status=000
    fi
    case "$http_status" in
      2??) ;;
      000|404|409|502|503|504)
        pending "Debezium Connect is not ready; inspect its service logs."
        return 1
        ;;
      *) fail "Debezium connector status check failed with HTTP $http_status." ;;
    esac
    state=$(jq -ser '
      if length != 1 or (.[0] | type) != "object" then error("invalid status")
      else .[0] end |
      if (.connector | type) != "object" or (.tasks | type) != "array" then
        error("invalid status")
      elif any(.tasks[]; type != "object") then error("invalid task")
      elif .connector.state == "FAILED" or any(.tasks[]; .state == "FAILED") then
        "failed"
      elif .connector.state == "RUNNING" and [.tasks[].state] == ["RUNNING"] then
        "running"
      else "pending" end
    ' "$work_directory/status.json" 2>/dev/null) ||
      fail "Debezium Connect returned malformed connector status."
    case "$state" in
      failed) fail "The $service outbox connector or task FAILED; inspect Debezium logs." ;;
      pending)
        pending "The $service outbox connector and task are not RUNNING yet."
        return 1
        ;;
    esac
  done

  remaining_timeout "$REQUEST_TIMEOUT_SECONDS" || return 1
  if ! timeout --foreground --signal=KILL "$operation_timeout" \
    bash -c 'exec 3<>"/dev/tcp/$1/$2"' _ "$playback_host" "$playback_port" >/dev/null 2>&1; then
    pending "The Playback server is not reachable yet; inspect its service logs."
    return 1
  fi
}

read -r -d '' projection_filter <<'JQ' || :
def fail($message): error({state: "error", message: $message});
def malformed($label):
  fail("\($label) contains malformed event metadata; repair the source explicitly.");

def identifier($label):
  if type == "string" and test("\\A[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\\z")
  then ascii_downcase else malformed($label) end;

# Compare UTC whole seconds and microseconds separately, avoiding floating-point
# rounding and jq's UTC-only, whole-second fromdateiso8601 parser.
def instant($label):
  if type != "string" then malformed($label) else . end
  | (capture("\\A(?<year>[0-9]{4})-(?<month>[0-9]{2})-(?<day>[0-9]{2})[Tt ](?<hour>[0-9]{2}):(?<minute>[0-9]{2}):(?<second>[0-9]{2})(?:[.,](?<fraction>[0-9]+))?(?<zone>Z|[+-][0-9]{2}:[0-9]{2})\\z") // malformed($label))
  | . as $parts
  | [$parts.year, $parts.month, $parts.day, $parts.hour, $parts.minute, $parts.second]
  | map(tonumber) as [$year, $month, $day, $hour, $minute, $second]
  | ($year % 4 == 0 and ($year % 100 != 0 or $year % 400 == 0)) as $leap
  | [31, (if $leap then 29 else 28 end), 31, 30, 31, 30, 31, 31, 30, 31, 30, 31] as $days
  | (if $parts.zone == "Z" then [0, 0, 1]
     else [($parts.zone[1:3] | tonumber), ($parts.zone[4:6] | tonumber),
           (if $parts.zone[0:1] == "-" then -1 else 1 end)] end) as [$zone_hour, $zone_minute, $zone_sign]
  | if $year < 1 or $month < 1 or $month > 12 or $day < 1 or $day > $days[$month - 1]
       or $hour > 23 or $minute > 59 or $second > 59 or $zone_hour > 23 or $zone_minute > 59
    then malformed($label)
    else
      ($year - 1) as $prior_year
      | ($prior_year * 365 + ($prior_year / 4 | floor) - ($prior_year / 100 | floor)
          + ($prior_year / 400 | floor) + ($days[:$month - 1] | add // 0) + $day - 1) as $day_number
      | [($day_number * 86400 + $hour * 3600 + $minute * 60 + $second
           - $zone_sign * ($zone_hour * 3600 + $zone_minute * 60)),
         (((($parts.fraction // "") + "000000")[:6]) | tonumber)]
    end;

def event_metadata($kind; $label):
  (if $kind == "user" then ["eventId", "occurredAt", "userId"]
   else ["eventId", "occurredAt", "courseId", "lessonId", "title", "durationSeconds", "isPreview", "objectKey"] end) as $fields
  | if type != "object" then malformed($label) else . end
  | . as $event
  | if all($fields[]; . as $field | $event | has($field)) then . else malformed($label) end
  | with_entries(select(.key as $key | $fields | index($key)))
  | .eventId |= identifier($label)
  | .occurredAt |= instant($label)
  | if $kind == "user" then .userId |= identifier($label)
    else
      .courseId |= identifier($label)
      | if (.lessonId | type != "string") or (.title | type != "string")
           or (.durationSeconds | type != "number") or (.isPreview | type != "boolean")
           or (.objectKey | type != "string") then malformed($label) else . end
      | if (.lessonId | length < 1 or length > 100 or (test("\\A[a-z0-9]+(?:-[a-z0-9]+)*\\z") | not))
           or (.title | length < 1 or length > 200 or (test("[^\\s]") | not))
           or (.durationSeconds | . < 1 or . > 2147483647 or floor != .)
           or (.objectKey | length < 1 or length > 1024 or (test("\\A[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\\.mp4\\z") | not))
        then malformed($label) else . end
    end;

def entity_key($kind):
  if $kind == "user" then .userId else "\(.courseId)/\(.lessonId)" end;

def index_events($kind; $label; $outbox):
  if type != "array" then malformed($label) else . end
  | reduce .[] as $row ({entities: {}, eventIds: {}};
      (if ($row | type) != "object" then malformed($label)
       elif $outbox then $row.event else $row end | event_metadata($kind; $label)) as $event
      | ($event | entity_key($kind)) as $key
      | if $outbox and ($row.payloadType != "object" or $row.aggregateType != $kind
           or $row.aggregateId != $key or ($row.rowId | identifier($label)) != $event.eventId)
        then malformed($label)
        elif (.entities | has($key)) or (.eventIds | has($event.eventId))
        then fail("\($label) contains duplicate immutable creation events.")
        else .entities[$key] = $event | .eventIds[$event.eventId] = true end)
  | .entities;

def expected_projections:
  if ($identity | length) != 1 then fail("The identity database returned malformed JSON.") else . end
  | $identity[0]
  | if type != "object" or (.userIds | type) != "array" then malformed("Identity outbox") else . end
  | . as $source
  | (.events | index_events("user"; "Identity outbox"; true)) as $users
  | ($source.userIds | map(identifier("Identity user IDs")) | unique) as $current_users
  | ($current_users - ($users | keys) | length) as $uncovered
  | if $uncovered > 0 then fail("Identity has \($uncovered) user(s) without a UserCreated outbox event. Replaying Kafka cannot create missing events; backfill them explicitly before retrying.") else . end
  | if ($catalog | length) != 1 then fail("The catalog database returned malformed JSON.") else . end
  | ($catalog[0] | index_events("lesson"; "Catalog outbox"; true)) as $lessons
  | {users: $users, lessons: $lessons};

def missing_projections($source; $kind; $field):
  (.[$field] | index_events($kind; "Playback \($kind) projection"; false)) as $projected
  | ([$source | to_entries[] | . as $entry
       | select(($projected | has($entry.key)) and $projected[$entry.key] != $entry.value)] | length) as $mismatched
  | if $mismatched > 0 then fail("Playback has \($mismatched) stale immutable \($kind) projection(s). Replaying creation events cannot overwrite existing rows; reconcile them explicitly. This check did not modify projections, progress, or favorites.")
    else (($source | keys) - ($projected | keys) | length) end;

try (
  expected_projections as $expected
  | if $mode == "expected" then {state: "ready", message: "Source outbox events are valid."}
    elif $mode == "compare" then
      if ($playback | length) != 1 then fail("The playback database returned malformed JSON.") else . end
      | $playback[0]
      | if type != "object" then malformed("Playback projection") else . end
      | . as $actual
      | missing_projections($expected.users; "user"; "users") as $missing_users
      | ($actual | missing_projections($expected.lessons; "lesson"; "lessons")) as $missing_lessons
      | if $missing_users > 0 or $missing_lessons > 0 then
          {state: "pending", message: "Waiting for Playback projections: \($missing_users) user(s), \($missing_lessons) lesson(s) missing."}
        else
          {state: "ready", message: "Playback projections are ready: \($expected.users | length) user(s), \($expected.lessons | length) lesson(s) match the current outbox events."}
        end
    else fail("Unknown projection validation mode.") end
) catch (
  if type == "object" and .state == "error" then .
  else {state: "error", message: "Projection metadata validation failed; inspect the source explicitly."} end
)
JQ

check_projections() {
  local mode=$1 state message exit_status
  remaining_timeout "$COMMAND_TIMEOUT_SECONDS" || return 1
  if timeout --foreground --signal=KILL "$operation_timeout" \
    jq -n --arg mode "$mode" --slurpfile identity "$work_directory/identity.json" \
      --slurpfile catalog "$work_directory/catalog.json" \
      --slurpfile playback "$work_directory/playback.json" "$projection_filter" \
      >"$work_directory/result.json" 2>/dev/null; then
    state=$(jq -er '.state' "$work_directory/result.json")
    message=$(jq -er '.message' "$work_directory/result.json")
    case "$state" in
      ready)
        if [[ "$mode" == compare ]]; then echo "$message"; fi
        ;;
      pending) pending "$message"; return 1 ;;
      error) fail "$message" ;;
      *) fail "Could not validate Playback projection metadata." ;;
    esac
  else
    exit_status=$?
    case "$exit_status" in
      124|137) pending "Projection validation timed out."; return 1 ;;
      *) fail "Could not validate Playback projection metadata." ;;
    esac
  fi
}

touch "$work_directory/playback.json"
while (( SECONDS < deadline )); do
  if database_json identity "$identity_query" && database_json catalog "$catalog_query" &&
    check_projections expected && check_services &&
    database_json playback "$playback_query" && check_projections compare; then
    exit 0
  fi
  remaining=$((deadline - SECONDS))
  if (( remaining > 0 )); then
    if (( remaining > POLL_SECONDS )); then remaining=$POLL_SECONDS; fi
    sleep "$remaining"
  fi
done
fail "Timed out after ${TIMEOUT_SECONDS}s. $last_pending"
