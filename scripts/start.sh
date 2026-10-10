#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$(cd -- "$script_directory/.." && pwd)"

build_images=false
case "${1:-}" in
  --build) build_images=true ;;
  --help|-h)
    echo "Usage: ./scripts/start.sh [--build]"
    echo "Pulls published images selected by LAB_VERSION, lab-release, or this Git commit."
    echo "--build uses local source. IMAGE_TAG and IMAGE_PREFIX override image coordinates."
    exit 0 ;;
  "") ;;
  *) echo "Unknown option: $1. See --help." >&2; exit 2 ;;
esac
[[ $# -le 1 ]] || { echo "Too many arguments. See --help." >&2; exit 2; }

for command_name in docker git; do
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
source "$script_directory/images.sh"

log_file="$project_directory/startup-$(date +%Y%m%d-%H%M%S)-$$.log"
source "$script_directory/progress.sh"
total_steps=14
if "$build_images"; then total_steps=15; fi
init_progress "$log_file" "$total_steps"
trap stop_spinner EXIT
trap 'stop_spinner; printf "\nStartup interrupted. Full log: %s\n" "$log_file" >&2; exit 130' INT
trap 'stop_spinner; printf "\nStartup stopped. Full log: %s\n" "$log_file" >&2; exit 143' TERM

wait_for_docker() {
  local deadline=$((SECONDS + 120))
  # Codespaces can run postStartCommand before Docker-in-Docker is ready.
  until docker info >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      echo "Docker did not become ready within 120 seconds. Start Docker or check its permissions/context, then retry." >&2
      return 1
    fi
    sleep 2
  done
}

validate_backend_images() {
  local service_name
  for service_name in status gateway identity catalog playback; do
    docker run --rm --network none \
      "$IMAGE_PREFIX/$service_name-service:$IMAGE_TAG" \
      -J--dry-run || return "$?"
  done
}

start_infrastructure() {
  docker compose up --detach --no-build postgres seaweedfs kafka lgtm || return "$?"
  docker compose up --detach --wait postgres seaweedfs || return "$?"
  bash "$project_directory/.devcontainer/configure-docker-network.sh" || return "$?"
}

show_url() {
  local address
  if address="$(docker compose port "$2" "$3" 2>> "$log_file")" && [[ -n "$address" ]]; then
    address="${address%%$'\n'*}"
    if [[ "${CODESPACES:-false}" == true ]]; then
      printf '  %-11s https://%s-%s.%s\n' "$1" "$CODESPACE_NAME" "${address##*:}" \
        "${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-app.github.dev}"
    else
      printf '  %-11s http://localhost:%s\n' "$1" "${address##*:}"
    fi
  fi
}

printf 'Starting the telemetry lab. The first run may take several minutes.\n'
printf 'Demo data is temporary and is cleared when its storage containers stop.\n'
printf 'Images: %s (override with IMAGE_TAG)\n' "$IMAGE_TAG"
printf 'Full log: %s\n\n' "$log_file"

run_step "Waiting for Docker" wait_for_docker
if "$build_images"; then
  run_step "Building workshop images from local source" bash "$script_directory/build-images.sh"
fi
run_step "Downloading workshop images" bash "$script_directory/pull-images.sh"
run_step "Checking backend launchers" validate_backend_images
run_step "Preparing Identity signing keys" bash "$script_directory/generate-identity-keys.sh"
run_step "Starting PostgreSQL, SeaweedFS, Kafka, and Grafana" start_infrastructure
run_step "Setting up the Identity outbox" bash "$script_directory/setup-identity-outbox.sh"
run_step "Setting up the Catalog outbox" bash "$script_directory/setup-catalog-outbox.sh"
run_step "Starting application and telemetry services" docker compose up --detach --no-build --remove-orphans
run_step "Uploading demo videos" docker compose run --rm --no-deps seaweedfs-seed
run_step "Initializing the catalog proxy path" docker compose run --rm --no-deps proxy-control reset
run_step "Waiting for the Identity outbox connector" bash "$script_directory/wait-outbox.sh" identity
run_step "Waiting for the Catalog outbox connector" bash "$script_directory/wait-outbox.sh" catalog
run_step "Waiting for Playback projections" docker compose run --rm --no-deps playback-ready
run_step "Waiting for application and Grafana health checks" docker compose up --detach --no-deps --no-build --wait \
  identity-service catalog-service playback-service gateway-service frontend lgtm

docker compose ps >> "$log_file" 2>&1 || true

printf '\nApplication ready! Startup completed in %ss.\n' "$((SECONDS - started_at))"
show_url "Application" frontend 8080
show_url "Grafana" lgtm 3000
