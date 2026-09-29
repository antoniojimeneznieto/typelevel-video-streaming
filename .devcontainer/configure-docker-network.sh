#!/usr/bin/env bash
set -euo pipefail

[[ "${CODESPACES:-false}" == true ]] || exit 0
iptables_version="$(iptables --version)"
[[ "$iptables_version" == *nf_tables* ]] || exit 0

forward_rules="$(sudo -n iptables-legacy -S FORWARD)"
[[ "${forward_rules%%$'\n'*}" == '-P FORWARD DROP' ]] || exit 0

container_id="$(docker compose ps --quiet seaweedfs)"
[[ -n "$container_id" ]] || { echo "Cannot find the SeaweedFS container." >&2; exit 1; }
network_ids="$(docker inspect --format '{{range .NetworkSettings.Networks}}{{println .NetworkID}}{{end}}' "$container_id")"

while IFS= read -r network_id; do
  [[ -n "$network_id" ]] || continue
  network="$(docker network inspect --format '{{.Driver}} {{index .Labels "com.docker.compose.network"}} {{.Id}} {{with index .Options "com.docker.network.bridge.name"}}{{.}}{{end}}' "$network_id")"
  read -r driver network_name network_id bridge <<< "$network"
  [[ "$driver" == bridge && "$network_name" == default ]] || continue
  bridge="${bridge:-br-${network_id:0:12}}"
  [[ "$bridge" =~ ^[a-zA-Z0-9_.-]+$ && ${#bridge} -le 15 ]] || {
    echo "Cannot configure forwarding for bridge: $bridge" >&2; exit 1;
  }

  # Permit traffic only within this lab's bridge; preserve every existing policy.
  if sudo -n iptables-legacy -C FORWARD -i "$bridge" -o "$bridge" -j ACCEPT 2>/dev/null; then
    exit 0
  else
    exit_code=$?
    [[ "$exit_code" == 1 ]] || exit "$exit_code"
  fi
  sudo -n iptables-legacy -I FORWARD 1 -i "$bridge" -o "$bridge" -j ACCEPT
  printf 'Enabled container communication within %s (Codespaces legacy firewall workaround).\n' "$bridge"
  exit 0
done <<< "$network_ids"

echo "Cannot find the lab's default Docker bridge." >&2
exit 1
