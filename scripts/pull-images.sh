#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$(cd -- "$script_directory/.." && pwd)"
cd "$project_directory"
source "$script_directory/images.sh"

printf 'Preparing lab images (%s). Already downloaded images will be reused.\n' "$IMAGE_TAG"
if ! docker compose --profile traffic --profile readiness --profile seed --profile proxy \
  pull --policy missing; then
  printf '\nCould not download lab images (%s).\n' "$IMAGE_TAG" >&2
  printf 'Check that the publishing workflow completed and that you can access %s.\n' "$IMAGE_PREFIX" >&2
  printf 'For private images, authenticate Docker with a token that can read packages.\n' >&2
  printf 'Retry with bash scripts/pull-images.sh.\n' >&2
  printf 'Alternatively, run ./scripts/start.sh --build to build this checkout locally.\n' >&2
  exit 1
fi
printf 'Lab images are ready.\n'
