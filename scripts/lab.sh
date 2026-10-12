#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$(cd -- "$script_directory/.." && pwd)"
source "$script_directory/version.sh"

local_cli_changes=false
# Compare against the selected release, including committed branch changes.
# A clean working tree can still contain a newer CLI than the cached release.
cli_release_revision="${LAB_VERSION#sha-}"
if ! git -C "$project_directory" rev-parse --verify "$cli_release_revision^{commit}" >/dev/null 2>&1 ||
   ! git -C "$project_directory" diff --quiet "$cli_release_revision" -- tools/lab-cli/src build.sbt; then
  local_cli_changes=true
fi

find_launcher() {
  # Source edits must use the locally staged CLI, even when a release is cached.
  if [[ "$local_cli_changes" != true ]]; then
    for candidate in "$project_directory"/.lab/cli/"$LAB_VERSION"/lab-cli-*/bin/lab-cli; do
      if [[ -x "$candidate" ]]; then printf '%s\n' "$candidate"; return 0; fi
    done
  fi
  for candidate in "$project_directory"/target/out/jvm/scala-*/lab-cli/universal/stage/bin/lab-cli; do
    if [[ -x "$candidate" ]]; then printf '%s\n' "$candidate"; return 0; fi
  done
  return 1
}

cli_jar_valid() {
  [[ -n "$1" && -f "$1" ]] &&
    jar tf "$1" | grep -Fx 'org/typelevel/video/streaming/lab/LabCli.class' >/dev/null
}

if [[ -n "${LAB_CLI_EXEC:-}" ]]; then
  launcher="$LAB_CLI_EXEC"
else
  launcher="$(find_launcher || true)"
  if [[ "$local_cli_changes" != true &&
        "$launcher" != "$project_directory"/.lab/cli/"$LAB_VERSION"/* &&
        ( -z "$launcher" || "$LAB_VERSION" == v* ) ]]; then
    if bash "$script_directory/install-cli.sh"; then
      launcher="$(find_launcher || true)"
    fi
  fi
  staged_jar=""
  if [[ -n "$launcher" && "$launcher" != "$project_directory"/.lab/cli/"$LAB_VERSION"/* ]]; then
    for candidate in "$(dirname "$launcher")"/../lib/org.typelevel.video.streaming.lab-cli-*.jar; do
      if [[ -f "$candidate" ]]; then staged_jar="$candidate"; break; fi
    done
  fi
  if [[ -z "$launcher" || ! -x "$launcher" ]] ||
    { [[ "$launcher" != "$project_directory"/.lab/cli/"$LAB_VERSION"/* ]] &&
      { ! cli_jar_valid "$staged_jar" ||
        [[ -n "$(find "$project_directory/tools/lab-cli/src" "$project_directory/build.sbt" -type f -newer "$staged_jar" -print -quit 2>/dev/null)" ]]; }; }; then
    build_log="$(mktemp)"
    printf 'Preparing the lab command (first run or CLI sources changed)...\n'
    build_task='labCli/stage'
    if [[ -n "$staged_jar" ]] && ! cli_jar_valid "$staged_jar"; then
      build_task=';labCli/clean;labCli/stage'
    fi
    if ! (cd "$project_directory" && sbt --batch "$build_task") >"$build_log" 2>&1; then
      cat "$build_log" >&2
      rm -f "$build_log"
      exit 1
    fi
    rm -f "$build_log"
    launcher="$(find_launcher)"
    if [[ "$launcher" != "$project_directory"/.lab/cli/"$LAB_VERSION"/* ]]; then
      staged_jar=""
      for candidate in "$(dirname "$launcher")"/../lib/org.typelevel.video.streaming.lab-cli-*.jar; do
        if [[ -f "$candidate" ]]; then staged_jar="$candidate"; break; fi
      done
      if ! cli_jar_valid "$staged_jar"; then
        echo 'The rebuilt lab CLI JAR is incomplete: LabCli.class is missing.' >&2
        exit 1
      fi
    fi
  fi
fi

[[ -x "$launcher" ]] || { echo "Lab CLI launcher is unavailable: $launcher" >&2; exit 1; }
if [[ $# -eq 1 && "$1" == help ]]; then set -- --help; fi
exec "$launcher" --root "$project_directory" "$@"
