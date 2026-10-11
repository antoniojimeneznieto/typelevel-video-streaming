#!/usr/bin/env bash
set -euo pipefail

project_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_directory"

npm --prefix frontend ci --no-audit --no-fund
if ! bash scripts/install-cli.sh; then
  sbt --batch 'labCli/stage'
fi
sbt --batch 'statusService/update; gatewayService/update; identityService/update; catalogService/update; playbackService/update; trafficGenerator/update; frontend/update; identityService/Compile/compile; playbackService/Compile/compile; catalogService/Compile/compile'
