#!/usr/bin/env bash
set -euo pipefail

project_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_directory"

npm --prefix frontend ci --no-audit --no-fund
sbt --batch 'statusService/update; gatewayService/update; identityService/update; catalogService/update; playbackService/update; trafficGenerator/update; frontend/update'
