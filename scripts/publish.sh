#!/usr/bin/env bash
# Start the GitHub Actions build for the current pushed branch or a release tag.
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$(cd -- "$script_directory/.." && pwd)"

if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
  echo "Usage: ./scripts/publish.sh [pushed-branch-or-v-tag]"
  echo "Builds lab images and a CLI archive in GitHub Actions. A v-tag also creates a release."
  exit 0
fi
[[ $# -le 1 ]] || { echo "Too many arguments. See --help." >&2; exit 2; }
command -v gh >/dev/null 2>&1 || { echo "GitHub CLI (gh) is required." >&2; exit 1; }

ref="${1:-$(git -C "$project_directory" branch --show-current)}"
[[ -n "$ref" ]] || { echo "Specify a pushed branch or v-tag." >&2; exit 2; }
gh workflow run publish-images.yml --repo "$(git -C "$project_directory" remote get-url origin | sed -E 's#^.*github.com[:/]([^/]+/[^/]+)(\.git)?$#\1#; s#\.git$##')" --ref "$ref"
printf 'Publishing started for %s. Follow it with: gh run list --workflow publish-images.yml --branch %s\n' "$ref" "$ref"
