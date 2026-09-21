#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$(cd -- "$script_directory/.." && pwd)"

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

log_file="$project_directory/startup-$(date +%Y%m%d-%H%M%S)-$$.log"
source "$script_directory/progress.sh"
init_progress "$log_file" 13
trap stop_spinner EXIT
trap 'stop_spinner; printf "\nStartup interrupted. Full log: %s\n" "$log_file" >&2; exit 130' INT
trap 'stop_spinner; printf "\nStartup stopped. Full log: %s\n" "$log_file" >&2; exit 143' TERM

validate_backend_images() {
  local service_name
  for service_name in status gateway identity catalog playback; do
    docker run --rm --network none \
      "typelevel-video-streaming/$service_name-service:local" \
      -J--dry-run || return "$?"
  done
}

show_url() {
  local address
  if address="$(docker compose port "$2" "$3" 2>> "$log_file")" && [[ -n "$address" ]]; then
    address="${address%%$'\n'*}"
    printf '  %-11s http://localhost:%s\n' "$1" "${address##*:}"
  fi
}

printf 'Starting the telemetry lab. The first run may take several minutes.\n'
printf 'Demo data is temporary and is cleared when its storage containers stop.\n'
printf 'Full log: %s\n\n' "$log_file"

run_step "Building backend images" \
  sbt 'statusService/Docker/publishLocal; gatewayService/Docker/publishLocal; identityService/Docker/publishLocal; catalogService/Docker/publishLocal; playbackService/Docker/publishLocal'
run_step "Checking backend launchers" validate_backend_images
run_step "Preparing Identity signing keys" bash "$script_directory/generate-identity-keys.sh"
run_step "Building frontend and startup helpers" \
  docker compose build frontend playback-ready identity-outbox-init
run_step "Starting PostgreSQL and MinIO" docker compose up --detach --wait postgres minio
run_step "Setting up the Identity outbox" bash "$script_directory/setup-identity-outbox.sh"
run_step "Setting up the Catalog outbox" bash "$script_directory/setup-catalog-outbox.sh"
run_step "Uploading demo videos" docker compose run --rm --no-deps minio-seed
run_step "Starting application and telemetry services" docker compose up --detach --remove-orphans
run_step "Waiting for the Identity outbox connector" bash "$script_directory/wait-outbox.sh" identity
run_step "Waiting for the Catalog outbox connector" bash "$script_directory/wait-outbox.sh" catalog
run_step "Waiting for Playback projections" docker compose run --rm --no-deps playback-ready
run_step "Waiting for application health checks" docker compose up --detach --no-deps --wait \
  identity-service catalog-service playback-service gateway-service frontend
docker compose ps >> "$log_file" 2>&1 || true

printf '\nApplication ready! Startup completed in %ss.\n' "$((SECONDS - started_at))"
show_url "Application" frontend 8080
show_url "Grafana" lgtm 3000
