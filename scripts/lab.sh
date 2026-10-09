#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$(cd -- "$script_directory/.." && pwd)"

find_launcher() {
  for candidate in "$project_directory"/target/out/jvm/scala-*/lab-cli/universal/stage/bin/lab-cli; do
    if [[ -x "$candidate" ]]; then printf '%s\n' "$candidate"; return 0; fi
  done
  return 1
}

if [[ -n "${LAB_CLI_EXEC:-}" ]]; then
  launcher="$LAB_CLI_EXEC"
else
  launcher="$(find_launcher || true)"
  staged_jar=""
  if [[ -n "$launcher" ]]; then
    for candidate in "$(dirname "$launcher")"/../lib/org.typelevel.video.streaming.lab-cli-*.jar; do
      if [[ -f "$candidate" ]]; then staged_jar="$candidate"; break; fi
    done
  fi
  if [[ -z "$launcher" || ! -x "$launcher" ]] ||
    [[ -z "$staged_jar" ]] ||
    [[ -n "$(find "$project_directory/tools/lab-cli/src" "$project_directory/build.sbt" -type f -newer "$staged_jar" -print -quit 2>/dev/null)" ]]; then
    build_log="$(mktemp)"
    if ! (cd "$project_directory" && sbt --batch 'labCli/stage') >"$build_log" 2>&1; then
      cat "$build_log" >&2
      rm -f "$build_log"
      exit 1
    fi
    rm -f "$build_log"
    launcher="$(find_launcher)"
  fi
fi

[[ -x "$launcher" ]] || { echo "Lab CLI launcher is unavailable: $launcher" >&2; exit 1; }
if [[ $# -eq 1 && "$1" == help ]]; then set -- --help; fi
exec "$launcher" --root "$project_directory" "$@"
