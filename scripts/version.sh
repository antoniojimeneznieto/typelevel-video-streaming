#!/usr/bin/env bash
# Shared version selection for published images and the packaged CLI.

if [[ -z "${LAB_VERSION:-}" ]]; then
  LAB_VERSION="$(git -C "$project_directory" describe --tags --exact-match --match 'v[0-9]*' 2>/dev/null || true)"
fi
if [[ -z "${LAB_VERSION:-}" && -f "$project_directory/lab-release" ]]; then
  LAB_VERSION="$(awk '!/^#/ && NF { print $1; exit }' "$project_directory/lab-release")"
fi
if [[ -z "${LAB_VERSION:-}" || "$LAB_VERSION" == auto ]]; then
  LAB_VERSION="sha-$(git -C "$project_directory" rev-parse HEAD)"
fi
if [[ ! "$LAB_VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ &&
      ! "$LAB_VERSION" =~ ^sha-[0-9a-f]{40}$ ]]; then
  echo "Invalid LAB_VERSION: $LAB_VERSION (expected vX.Y.Z or sha-<40-character commit>)." >&2
  return 2
fi
export LAB_VERSION
