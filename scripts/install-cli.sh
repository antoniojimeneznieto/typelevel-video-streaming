#!/usr/bin/env bash
# Install the CLI built for this exact checkout, when a published copy exists.
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$(cd -- "$script_directory/.." && pwd)"
sha="$(git -C "$project_directory" rev-parse HEAD)"
tag="$(git -C "$project_directory" describe --tags --exact-match --match 'v[0-9]*' 2>/dev/null || true)"
archive="lab-cli-$sha.zip"
if [[ -n "$tag" ]]; then archive="lab-cli-$tag.zip"; fi
repository="$(git -C "$project_directory" remote get-url origin | sed -E 's#^.*github.com[:/]([^/]+/[^/]+)(\.git)?$#\1#; s#\.git$##')"
temporary_directory="$(mktemp -d)"
trap 'rm -rf -- "$temporary_directory"' EXIT

if [[ -n "$tag" ]]; then
  base_url="https://github.com/$repository/releases/download/$tag"
  if ! curl --fail --silent --show-error --location "$base_url/$archive" -o "$temporary_directory/$archive" ||
     ! curl --fail --silent --show-error --location "$base_url/SHA256SUMS" -o "$temporary_directory/SHA256SUMS"; then
    command -v gh >/dev/null 2>&1 || { echo "Install gh to access this private release." >&2; exit 1; }
    gh release download "$tag" --repo "$repository" --pattern "$archive" --pattern SHA256SUMS --dir "$temporary_directory" --clobber
  fi
else
  command -v gh >/dev/null 2>&1 || { echo "No release tag at this commit; gh is needed to download its workflow artifact." >&2; exit 1; }
  run_id="$(gh run list --repo "$repository" --workflow publish-images.yml --commit "$sha" --limit 10 \
    --json databaseId,conclusion --jq '[.[] | select(.conclusion == "success")][0].databaseId // empty')"
  [[ -n "$run_id" ]] || { echo "No completed CLI publishing run for $sha." >&2; exit 1; }
  gh run download "$run_id" --repo "$repository" --name "lab-cli-$sha" --dir "$temporary_directory"
fi

cd "$temporary_directory"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum --check SHA256SUMS
else
  shasum -a 256 --check SHA256SUMS
fi
destination="$project_directory/.lab/cli/$sha"
mkdir -p "$destination"
unzip -q -o "$archive" -d "$destination"
printf 'Installed the published CLI for %s.\n' "$sha"
