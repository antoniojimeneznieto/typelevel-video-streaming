#!/usr/bin/env bash
# Install the CLI for the selected lab version, when a published copy exists.
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$(cd -- "$script_directory/.." && pwd)"
source "$script_directory/version.sh"
archive="lab-cli-$LAB_VERSION.zip"
repository="${LAB_REPOSITORY:-antoniojimeneznieto/typelevel-video-streaming}"
temporary_directory="$(mktemp -d)"
trap 'rm -rf -- "$temporary_directory"' EXIT

if [[ "$LAB_VERSION" == v* ]]; then
  base_url="https://github.com/$repository/releases/download/$LAB_VERSION"
  if ! curl --fail --silent --show-error --location "$base_url/$archive" -o "$temporary_directory/$archive" ||
     ! curl --fail --silent --show-error --location "$base_url/SHA256SUMS" -o "$temporary_directory/SHA256SUMS"; then
    command -v gh >/dev/null 2>&1 || { echo "Install gh to access this private release." >&2; exit 1; }
    gh release download "$LAB_VERSION" --repo "$repository" --pattern "$archive" --pattern SHA256SUMS --dir "$temporary_directory" --clobber
  fi
else
  command -v gh >/dev/null 2>&1 || { echo "No release tag at this commit; gh is needed to download its workflow artifact." >&2; exit 1; }
  source_sha="${LAB_VERSION#sha-}"
  run_id="$(gh run list --repo "$repository" --workflow publish-images.yml --commit "$source_sha" --limit 10 \
    --json databaseId,conclusion --jq '[.[] | select(.conclusion == "success")][0].databaseId // empty')"
  [[ -n "$run_id" ]] || { echo "No completed CLI publishing run for $source_sha." >&2; exit 1; }
  gh run download "$run_id" --repo "$repository" --name "lab-cli-$source_sha" --dir "$temporary_directory"
  archive="lab-cli-$source_sha.zip"
fi

cd "$temporary_directory"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum --check SHA256SUMS
else
  shasum -a 256 --check SHA256SUMS
fi
destination="$project_directory/.lab/cli/$LAB_VERSION"
mkdir -p "$destination"
unzip -q -o "$archive" -d "$destination"
printf 'Installed the published CLI for %s.\n' "$LAB_VERSION"
