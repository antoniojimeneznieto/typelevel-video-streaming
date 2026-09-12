#!/usr/bin/env bash
set -euo pipefail

service=${OUTBOX_SERVICE:-identity}
fail() { echo "${service^} outbox setup failed: $*" >&2; exit 1; }
case "$service" in
  identity|catalog) ;;
  *) echo "OUTBOX_SERVICE must be identity or catalog" >&2; exit 1 ;;
esac

script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
connector="$service-outbox"
config_file="$script_directory/$connector.json"
connect_url=${DEBEZIUM_CONNECT_URL:-http://debezium-connect:8083}
while [[ "$connect_url" == */ ]]; do connect_url=${connect_url%/}; done
case "$connect_url" in
  http://*|https://*) ;;
  *) fail "DEBEZIUM_CONNECT_URL must use HTTP or HTTPS" ;;
esac

readonly TIMEOUT_SECONDS=120 REQUEST_TIMEOUT_SECONDS=5 POLL_SECONDS=2
for required_command in curl jq; do
  command -v "$required_command" >/dev/null || fail "Missing required command: $required_command"
done
jq -se 'length == 1 and (.[0] | type == "object")' "$config_file" >/dev/null 2>&1 ||
  fail "Cannot read a valid connector configuration"

umask 077
response_file=$(mktemp)
trap 'rm -f -- "$response_file"' EXIT

request() {
  local deadline=$1 method=$2 path=$3
  shift 3
  local remaining=$((deadline - SECONDS))
  (( remaining > 0 )) || return 1
  (( remaining <= REQUEST_TIMEOUT_SECONDS )) || remaining=$REQUEST_TIMEOUT_SECONDS
  # Never print response bodies: configs and task traces can contain credentials.
  if ! http_status=$(curl --disable --silent --connect-timeout "$remaining" \
    --max-time "$remaining" --output "$response_file" --write-out '%{http_code}' \
    --header 'Content-Type: application/json' --request "$method" \
    "$connect_url$path" "$@" 2>/dev/null); then
    http_status=000
  fi
}

pause() {
  local remaining=$(($1 - SECONDS))
  if (( remaining > 0 )); then
    (( remaining <= POLL_SECONDS )) || remaining=$POLL_SECONDS
    sleep "$remaining"
  fi
}

register() {
  local deadline=$((SECONDS + TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    # Send the file verbatim; ${env:...} placeholders belong to Kafka Connect.
    request "$deadline" PUT "/connectors/$connector/config" \
      --data-binary "@$config_file" || break
    case "$http_status" in
      2??) return ;;
      000|409|502|503|504) ;;
      *) fail "Connector registration failed with HTTP $http_status; inspect Debezium configuration and logs" ;;
    esac
    pause "$deadline"
  done
  fail "Timed out registering the connector"
}

wait_until_running() {
  local deadline=$((SECONDS + TIMEOUT_SECONDS)) consecutive_running=0 state
  while (( SECONDS < deadline )); do
    request "$deadline" GET "/connectors/$connector/status" || break
    case "$http_status" in
      2??)
        state=$(jq -ser '
          if length != 1 or (.[0] | type) != "object" then error("invalid status")
          else .[0] end |
          if (.connector | type) != "object" or (.tasks | type) != "array" then
            error("invalid status")
          elif (.connector.state | type) != "string" or
               any(.tasks[]; (.state | type) != "string") then
            error("invalid state")
          elif .connector.state == "FAILED" or any(.tasks[]; .state == "FAILED") then
            "failed"
          elif .connector.state == "RUNNING" and [.tasks[].state] == ["RUNNING"] then
            "running"
          else "pending" end
        ' "$response_file" 2>/dev/null) || fail "Connector returned malformed status JSON"
        case "$state" in
          failed) fail "Connector or task failed; inspect Debezium logs and run scripts/setup-$service-outbox.sh if database setup is missing" ;;
          running)
            consecutive_running=$((consecutive_running + 1))
            if (( consecutive_running >= 3 )); then return; fi
            ;;
          pending) consecutive_running=0 ;;
        esac
        ;;
      000|404|409|502|503|504) consecutive_running=0 ;;
      *) fail "Connector status check failed with HTTP $http_status" ;;
    esac
    pause "$deadline"
  done
  fail "Timed out waiting for the connector and task to run"
}

register
wait_until_running
echo "${service^} outbox connector and task are RUNNING."
