#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$(cd -- "$script_directory/.." && pwd)"

cd "$project_directory"

service_name="${1:-identity}"
case "$service_name" in
  identity|catalog) ;;
  *)
    echo "Expected outbox service: identity or catalog" >&2
    exit 1
    ;;
esac
initializer_service="$service_name-outbox-init"

initializer_id="$(docker compose ps --all --quiet "$initializer_service")"
if [[ ! "$initializer_id" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Expected exactly one $service_name outbox initializer container; start the Compose services first." >&2
  exit 1
fi

if ! initializer_exit_code="$(docker wait "$initializer_id")"; then
  echo "Could not wait for the $service_name outbox initializer." >&2
  exit 1
fi

if [[ "$initializer_exit_code" != "0" ]]; then
  echo "$service_name outbox initialization failed. Inspect docker compose logs $initializer_service." >&2
  exit 1
fi

echo "$service_name outbox initialization completed successfully."
