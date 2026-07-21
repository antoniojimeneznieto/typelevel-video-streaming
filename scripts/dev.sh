#!/usr/bin/env bash

set -euo pipefail

dev_script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
dev_repo_root=$(cd -- "$dev_script_dir/.." && pwd)

if [[ ! -f "$dev_repo_root/build.sbt" ]]; then
  printf 'Could not find build.sbt in %s.\n' "$dev_repo_root" >&2
  exit 1
fi

dev_port() {
  local dev_name=$1
  local dev_input=$2
  local dev_value=$dev_input
  local dev_leading_zeros

  if [[ "$dev_value" =~ ^[0-9]+$ ]]; then
    dev_leading_zeros=${dev_value%%[!0]*}
    dev_value=${dev_value#"$dev_leading_zeros"}
    dev_value=${dev_value:-0}
  fi

  if [[ ! "$dev_value" =~ ^[0-9]+$ ]] ||
    (( ${#dev_value} > 5 )) ||
    (( 10#$dev_value < 1 || 10#$dev_value > 65535 )); then
    printf '%s must be an integer between 1 and 65535 (got %q).\n' \
      "$dev_name" "$dev_input" >&2
    return 1
  fi

  printf '%d\n' "$((10#$dev_value))"
}

dev_frontend_port=$(dev_port FRONTEND_PORT "${FRONTEND_PORT-4500}")
dev_status_service_port=$(dev_port STATUS_SERVICE_PORT "${STATUS_SERVICE_PORT-18080}")
dev_user_service_port=$(dev_port USER_SERVICE_PORT "${USER_SERVICE_PORT-18081}")

dev_sbt_pid=
dev_http_pid=
dev_compose_started=false

dev_cleanup() {
  local dev_exit_status=$?

  trap - EXIT INT TERM

  for dev_child_pid in "$dev_sbt_pid" "$dev_http_pid"; do
    if [[ -n "$dev_child_pid" ]]; then
      kill "$dev_child_pid" 2>/dev/null || true
    fi
  done

  if [[ "$dev_compose_started" == true ]]; then
    STATUS_SERVICE_PORT="$dev_status_service_port" \
      USER_SERVICE_PORT="$dev_user_service_port" \
      docker compose down || true
  fi

  for dev_child_pid in "$dev_sbt_pid" "$dev_http_pid"; do
    if [[ -n "$dev_child_pid" ]]; then
      wait "$dev_child_pid" 2>/dev/null || true
    fi
  done

  exit "$dev_exit_status"
}

trap dev_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cd "$dev_repo_root"

printf 'Building backend images and frontend bundle...\n'
sbt \
  statusService/Docker/publishLocal \
  userService/Docker/publishLocal \
  frontend/fastLinkJS

printf 'Starting backend services...\n'
STATUS_SERVICE_PORT="$dev_status_service_port" \
  USER_SERVICE_PORT="$dev_user_service_port" \
  docker compose up -d
dev_compose_started=true

printf 'Watching frontend sources...\n'
sbt '~frontend/fastLinkJS' &
dev_sbt_pid=$!

printf 'Serving frontend on port %s...\n' "$dev_frontend_port"
python3 -m http.server \
  --directory "$dev_repo_root/frontend" \
  "$dev_frontend_port" &
dev_http_pid=$!

printf '\n'
printf 'Application:    http://localhost:%s/\n' "$dev_frontend_port"
printf 'Status service: http://localhost:%s/api/health\n' "$dev_status_service_port"
printf 'User service:   http://localhost:%s/api/account/profile\n' "$dev_user_service_port"
printf '\n'
printf 'Press Ctrl+C to stop everything.\n'

if wait -n; then
  dev_child_exit_status=0
else
  dev_child_exit_status=$?
fi

printf 'A development process exited; stopping everything.\n' >&2
exit "$dev_child_exit_status"
