#!/usr/bin/env bash
# Shared terminal output for setup.sh and start.sh (Bash 3.2 compatible).

init_progress() {
  log_file="$1"
  total_steps="${2:-}"
  started_at=$SECONDS
  step=0
  spinner_pid=""
  interactive=false
  if [[ -t 1 && "${TERM:-dumb}" != dumb ]]; then
    interactive=true
  fi
  : > "$log_file"
}

stop_spinner() {
  if [[ -n "${spinner_pid:-}" ]]; then
    kill "$spinner_pid" 2>/dev/null || true
    wait "$spinner_pid" 2>/dev/null || true
    spinner_pid=""
    printf '\r\033[2K'
  fi
}

show_progress() {
  local frames='|/-\' frame=0
  while true; do
    printf '\r[%s] %s %s (%ss)' \
      "$step_label" "${frames:frame:1}" "$stage" "$((SECONDS - step_started))"
    frame=$(((frame + 1) % 4))
    sleep 0.2
  done
}

run_step() {
  local output_mode=quiet
  if [[ "$1" == --interactive ]]; then
    output_mode=interactive
    shift
  fi
  local stage="$1" step_started=$SECONDS exit_code=0 step_label
  shift
  step=$((step + 1))
  step_label="$step"
  if [[ -n "$total_steps" ]]; then
    printf -v step_label '%2d/%d' "$step" "$total_steps"
  fi
  printf '\n[%s] %s\n' "$step_label" "$stage" >> "$log_file"

  # Interactive installers keep their prompts visible, without a spinner.
  # Commands stay in the foreground so Ctrl-C reaches them normally.
  # Shell functions passed here must explicitly return on command failures.
  if [[ "$output_mode" == interactive ]]; then
    printf '[%s] %s (interactive)...\n' "$step_label" "$stage"
    "$@" 2>&1 | tee -a "$log_file" || exit_code=$?
  else
    if [[ "$interactive" == true ]]; then
      show_progress &
      spinner_pid=$!
    else
      printf '[%s] %s...\n' "$step_label" "$stage"
    fi
    "$@" >> "$log_file" 2>&1 || exit_code=$?
  fi

  stop_spinner
  if [[ "$exit_code" == 0 ]]; then
    printf '[%s] OK %s (%ss)\n' "$step_label" "$stage" "$((SECONDS - step_started))"
  else
    printf '[%s] FAILED %s (exit %s)\n\nLast 30 log lines:\n' \
      "$step_label" "$stage" "$exit_code" >&2
    tail -n 30 "$log_file" >&2
    printf '\nFull log: %s\n' "$log_file" >&2
    exit "$exit_code"
  fi
}
