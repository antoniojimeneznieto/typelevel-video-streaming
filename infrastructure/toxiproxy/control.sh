#!/bin/sh
set -eu

api=http://toxiproxy:8474
proxy=http://toxiproxy:8666

check() {
  curl --fail --silent --show-error --max-time 3 "$api/version" >/dev/null
  curl --fail --silent --show-error --max-time 5 "$proxy/courses?limit=1" >/dev/null
  echo "Catalog proxy and upstream are reachable."
}

reset() {
  curl --fail --silent --show-error --max-time 5 -X POST "$api/reset" >/dev/null
  curl --fail --silent --show-error --max-time 5 \
    -H 'Content-Type: application/json' \
    --data-binary @/etc/toxiproxy/proxies.json "$api/populate" >/dev/null
}

duration_ms() {
  case "$1" in
    ''|*[!0-9]*) echo "Milliseconds must be a positive integer." >&2; exit 2 ;;
  esac
  if [ "$1" -eq 0 ] || [ "$1" -gt 9999 ]; then
    echo "Milliseconds must be between 1 and 9999." >&2
    exit 2
  fi
}

case "${1:-}" in
  check)
    [ "$#" -eq 1 ] || exit 2
    check
    ;;
  status)
    [ "$#" -eq 1 ] || exit 2
    curl --fail --silent --show-error --max-time 5 "$api/proxies/catalog"
    echo
    ;;
  reset)
    [ "$#" -eq 1 ] || exit 2
    reset
    check
    ;;
  latency)
    [ "$#" -eq 2 ] || exit 2
    duration_ms "$2"
    reset
    curl --fail --silent --show-error --max-time 5 \
      -H 'Content-Type: application/json' \
      --data "{\"name\":\"catalog-latency\",\"type\":\"latency\",\"stream\":\"downstream\",\"attributes\":{\"latency\":$2,\"jitter\":0}}" \
      "$api/proxies/catalog/toxics"
    echo
    ;;
  timeout)
    [ "$#" -eq 2 ] || exit 2
    duration_ms "$2"
    reset
    curl --fail --silent --show-error --max-time 5 \
      -H 'Content-Type: application/json' \
      --data "{\"name\":\"catalog-timeout\",\"type\":\"timeout\",\"stream\":\"downstream\",\"attributes\":{\"timeout\":$2}}" \
      "$api/proxies/catalog/toxics"
    echo
    ;;
  down)
    [ "$#" -eq 1 ] || exit 2
    reset
    curl --fail --silent --show-error --max-time 5 \
      -X PATCH -H 'Content-Type: application/json' \
      --data '{"enabled":false}' "$api/proxies/catalog"
    echo
    ;;
  *)
    echo "Usage: control.sh {check|status|reset|latency MS|timeout MS|down}" >&2
    exit 2
    ;;
esac
