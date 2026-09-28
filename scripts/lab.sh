#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$(cd -- "$script_directory/.." && pwd)"
cd "$project_directory"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/lab.sh start
  ./scripts/lab.sh status
  ./scripts/lab.sh stop
  ./scripts/lab.sh traffic build
  ./scripts/lab.sh traffic run [generator options]
  ./scripts/lab.sh traffic start [generator options]
  ./scripts/lab.sh traffic status
  ./scripts/lab.sh traffic stop
  ./scripts/lab.sh proxy check
  ./scripts/lab.sh proxy status
  ./scripts/lab.sh proxy latency --milliseconds 750
  ./scripts/lab.sh proxy timeout --milliseconds 3000
  ./scripts/lab.sh proxy down
  ./scripts/lab.sh proxy reset

start builds and seeds the application stack; stop shuts it down and clears ephemeral data.
Build the traffic image once, then start the application before running traffic.
traffic run defaults to 3 minutes; traffic start runs in the background until stopped.
Options include --rate 5, --duration 3m, --max-concurrent 128, --request-timeout 30s.
traffic status shows the background container and its latest JSON reports.
proxy commands affect only the gateway-to-catalog connection; reset restores it.
EOF
}

if [[ $# -eq 0 ]]; then
  usage >&2
  exit 2
fi
container_name="typelevel-video-streaming-lab-traffic"

case "$1" in
  start)
    [[ $# -eq 1 ]] || { usage >&2; exit 2; }
    exec "$script_directory/start.sh"
    ;;
  status)
    [[ $# -eq 1 ]] || { usage >&2; exit 2; }
    exec docker compose ps
    ;;
  stop)
    [[ $# -eq 1 ]] || { usage >&2; exit 2; }
    if docker container inspect "$container_name" >/dev/null 2>&1 &&
      [[ "$(docker inspect --format '{{.State.Running}}' "$container_name")" == true ]]; then
      docker stop --time 40 "$container_name" >/dev/null
    fi
    exec docker compose down
    ;;
  help|--help|-h)
    [[ $# -eq 1 ]] || { usage >&2; exit 2; }
    usage
    exit 0
    ;;
  traffic)
    [[ $# -ge 2 ]] || { usage >&2; exit 2; }
    ;;
  proxy)
    [[ $# -ge 2 ]] || { usage >&2; exit 2; }
    shift
    action="$1"
    shift
    case "$action" in
      check|status|down|reset)
        [[ $# -eq 0 ]] || { usage >&2; exit 2; }
        exec docker compose run --rm --no-deps -T proxy-control "$action"
        ;;
      latency|timeout)
        [[ $# -eq 2 && "$1" == --milliseconds ]] || { usage >&2; exit 2; }
        [[ "$2" =~ ^[0-9]+$ ]] || { echo "Milliseconds must be a positive integer." >&2; exit 2; }
        exec docker compose run --rm --no-deps -T proxy-control "$action" "$2"
        ;;
      *) usage >&2; exit 2 ;;
    esac
    ;;
  *) usage >&2; exit 2 ;;
esac

action="$2"
shift 2

if [[ "$action" == run || "$action" == start ]]; then
  # Decline uses the last value, so explicit user options override these defaults.
  set -- --base-url http://gateway-service:8084 "$@"
  if [[ "$action" == start ]]; then set -- --duration infinite "$@"; fi
fi

case "$action" in
  build)
    [[ $# -eq 0 ]] || { usage >&2; exit 2; }
    exec sbt 'trafficGenerator/Docker/publishLocal'
    ;;
  run)
    exec docker compose run --rm --no-deps -T traffic-generator "$@"
    ;;
  start)
    # A named container prevents accidentally starting a second background run.
    # Keep stopped containers for inspection until the next explicit start.
    if docker container inspect "$container_name" >/dev/null 2>&1; then
      if [[ "$(docker inspect --format '{{.State.Running}}' "$container_name")" == true ]]; then
        echo "Traffic is already running. Use ./scripts/lab.sh traffic stop first." >&2
        exit 2
      fi
      docker container rm "$container_name" >/dev/null
    fi
    exec docker compose run --detach --no-deps --name "$container_name" traffic-generator \
      "$@"
    ;;
  status)
    [[ $# -eq 0 ]] || { usage >&2; exit 2; }
    docker inspect --format 'state={{.State.Status}} exit_code={{.State.ExitCode}}' "$container_name"
    exec docker logs --tail 5 "$container_name"
    ;;
  stop)
    [[ $# -eq 0 ]] || { usage >&2; exit 2; }
    exec docker stop --time 40 "$container_name"
    ;;
  *) usage >&2; exit 2 ;;
esac
