#!/usr/bin/env bash
set -euo pipefail

stage="preparing startup"
trap 'exit_code=$?; printf "Startup failed while %s (line %s, exit %s).\n" "$stage" "$LINENO" "$exit_code" >&2; exit "$exit_code"' ERR

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$(cd -- "$script_directory/.." && pwd)"
lock_directory="$project_directory/.start.lock.d"
lock_acquired=false

cleanup() {
  if [[ "$lock_acquired" == true ]]; then
    rm -f "$lock_directory/pid"
    rmdir "$lock_directory" 2>/dev/null || true
  fi
}
trap cleanup EXIT

for command_name in java docker sbt; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Required command not found: $command_name" >&2
    exit 1
  fi
done

if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose is required. Install a Docker distribution that includes the Compose plugin." >&2
  exit 1
fi

cd "$project_directory"

if ! mkdir "$lock_directory" 2>/dev/null; then
  owner_pid=""
  if [[ -f "$lock_directory/pid" ]]; then
    IFS= read -r owner_pid < "$lock_directory/pid" || true
  fi

  if [[ ! "$owner_pid" =~ ^[0-9]+$ ]]; then
    echo "Could not verify the startup lock at $lock_directory. Remove it if no startup is running." >&2
    exit 1
  fi

  if kill -0 "$owner_pid" 2>/dev/null; then
    echo "Another startup is already running (process $owner_pid)." >&2
    exit 1
  fi

  rm -f "$lock_directory/pid"
  if ! rmdir "$lock_directory" 2>/dev/null || ! mkdir "$lock_directory" 2>/dev/null; then
    echo "Could not acquire the startup lock at $lock_directory. Remove it if no startup is running." >&2
    exit 1
  fi
fi
lock_acquired=true
printf '%s\n' "$$" > "$lock_directory/pid"

echo "Demo startup: PostgreSQL, MinIO, and Kafka use ephemeral container storage."
echo "Stopping their containers clears runtime data; this script does not reset a running stack."

stage="building backend images"
echo "$stage"
sbt 'statusService/Docker/publishLocal; identityService/Docker/publishLocal; catalogService/Docker/publishLocal; playbackService/Docker/publishLocal'

stage="validating backend image entrypoints"
echo "$stage"
# Load each image's main class without starting the application.
for service_name in status identity catalog playback; do
  docker run --rm --network none \
    "typelevel-video-streaming/$service_name-service:local" \
    -J--dry-run
done

stage="preparing Identity signing keys"
echo "$stage"
bash "$script_directory/generate-identity-keys.sh"

stage="building frontend and initialization images"
echo "$stage"
docker compose build frontend playback-ready identity-outbox-init

stage="starting PostgreSQL and MinIO"
echo "$stage"
docker compose up --detach --wait postgres minio
stage="setting up the Identity outbox"
echo "$stage"
bash "$script_directory/setup-identity-outbox.sh"
stage="setting up the Catalog outbox"
echo "$stage"
bash "$script_directory/setup-catalog-outbox.sh"

stage="uploading demo videos to MinIO"
echo "$stage"
docker compose run --rm --no-deps minio-seed

stage="starting all application and infrastructure services"
echo "$stage"
docker compose up --detach --remove-orphans
stage="waiting for the Identity outbox connector"
bash "$script_directory/wait-outbox.sh" identity
stage="waiting for the Catalog outbox connector"
bash "$script_directory/wait-outbox.sh" catalog
stage="waiting for Playback projections"
docker compose run --rm --no-deps playback-ready
stage="waiting for the frontend"
docker compose up --detach --no-deps --wait frontend
docker compose ps

echo "Application startup completed successfully."
