#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$(cd -- "$script_directory/.." && pwd)"

if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
  cat <<'EOF'
Usage: ./scripts/build-images.sh [service ...]

Build all workshop images, or only the named services, using the current source.
Services: status-service gateway-service identity-service catalog-service
          playback-service traffic-generator frontend outbox-init playback-ready
IMAGE_PREFIX and IMAGE_TAG override the registry and version used by Compose.
To rebuild and restart one application service: ./scripts/lab.sh rebuild catalog-service
EOF
  exit 0
fi

source "$script_directory/images.sh"
cd "$project_directory"

if [[ $# -eq 0 ]]; then
  set -- status-service gateway-service identity-service catalog-service playback-service \
    traffic-generator frontend outbox-init playback-ready
fi

sbt_tasks=""
backend_images=()
compose_services=()
for service_name in "$@"; do
  project_name=""
  case "$service_name" in
    status-service) project_name=statusService ;;
    gateway-service) project_name=gatewayService ;;
    identity-service) project_name=identityService ;;
    catalog-service) project_name=catalogService ;;
    playback-service) project_name=playbackService ;;
    traffic-generator) project_name=trafficGenerator ;;
    frontend|playback-ready) compose_services+=("$service_name") ;;
    outbox-init) compose_services+=(identity-outbox-init) ;;
    *) echo "Unknown service: $service_name. See --help." >&2; exit 2 ;;
  esac
  if [[ -n "$project_name" ]]; then
    sbt_tasks="${sbt_tasks}${sbt_tasks:+; }$project_name/Docker/publishLocal"
    backend_images+=("$service_name")
  fi
done

if [[ -n "$sbt_tasks" ]]; then
  sbt --batch "$sbt_tasks"
  for service_name in "${backend_images[@]}"; do
    docker tag "typelevel-video-streaming/$service_name:local" \
      "$IMAGE_PREFIX/$service_name:$IMAGE_TAG"
  done
fi
if [[ ${#compose_services[@]} -gt 0 ]]; then
  docker compose build "${compose_services[@]}"
fi
