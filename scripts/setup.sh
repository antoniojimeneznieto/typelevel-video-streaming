#!/usr/bin/env bash
# Install workshop prerequisites, never start or reset the application stack.
set -euo pipefail

check_only=false
case "${1:-}" in
  --check) check_only=true ;;
  --help|-h)
    echo "Usage: bash scripts/setup.sh [--check]"
    echo "Supports macOS, Ubuntu 22.04/24.04/26.04, and Debian 12/13 (including WSL 2)."
    echo "--check reports readiness without installing or changing anything."
    exit 0 ;;
  "") ;;
  *) echo "Unknown option: $1" >&2; exit 1 ;;
esac
[[ $# -le 1 ]] || { echo "Too many arguments" >&2; exit 1; }

fail() { echo "$*" >&2; exit 1; }
confirm() {
  local answer
  read -r -p "$1 [y/N] " answer || return 1
  [[ "$answer" == y || "$answer" == Y ]]
}
has() { command -v "$1" >/dev/null 2>&1; }

jdk_ready() {
  local java_command=java javac_command=javac runtime compiler
  if [[ -n "${JAVA_HOME:-}" ]]; then
    java_command="$JAVA_HOME/bin/java"
    javac_command="$JAVA_HOME/bin/javac"
  fi
  runtime="$("$java_command" -version 2>&1)" || return 1
  compiler="$("$javac_command" -version 2>&1)" || return 1
  [[ "$runtime" =~ version[[:space:]]+\"([0-9]+) ]] || return 1
  (( BASH_REMATCH[1] >= 17 )) || return 1
  [[ "$compiler" =~ javac[[:space:]]+([0-9]+) ]] || return 1
  (( BASH_REMATCH[1] >= 17 )) && has java
}

platform="$(uname -s)"
is_wsl=false
if [[ -n "${WSL_DISTRO_NAME:-}" || -n "${WSL_INTEROP:-}" ]] ||
  grep -qi microsoft /proc/sys/kernel/osrelease 2>/dev/null; then
  is_wsl=true
fi

report() {
  local ready=true
  if jdk_ready; then echo "JDK 17+: OK"; else
    echo "JDK 17+: missing or incompatible (check PATH and JAVA_HOME)"; ready=false
  fi
  if has sbt; then echo "sbt: OK"; else echo "sbt: missing from PATH"; ready=false; fi
  if has docker && docker compose version >/dev/null 2>&1; then
    echo "Docker Compose: OK"
  else
    echo "Docker Compose: missing or unavailable"; ready=false
  fi
  if has docker && docker buildx version >/dev/null 2>&1; then
    echo "Docker Buildx: OK"
  else
    echo "Docker Buildx: missing or unavailable"; ready=false
  fi
  if has docker && docker info >/dev/null 2>&1; then
    echo "Docker daemon: reachable"
  else
    echo "Docker daemon: unavailable (start Docker or check your permissions/context)"; ready=false
  fi
  "$ready"
}

if report; then
  echo "Ready. Run ./scripts/start.sh from the repository root."
  exit 0
elif "$check_only"; then
  exit 1
fi

[[ "$platform" == Darwin || "$platform" == Linux ]] ||
  fail "Use scripts/setup.ps1 on Windows; otherwise install the prerequisites manually."
(( EUID != 0 )) || fail "Run this script as your normal user, not with sudo. It requests sudo when needed."
has sudo || fail "sudo is required for installation. Ask your administrator to install the prerequisites."

need_java=false; need_sbt=false; need_docker=false
jdk_ready || need_java=true
has sbt || need_sbt=true
has docker || need_docker=true
if "$is_wsl"; then need_docker=false; fi
if [[ "$platform" == Darwin ]] &&
  [[ -d /Applications/Docker.app || -d "$HOME/Applications/Docker.app" ]]; then
  need_docker=false
fi

if "$need_java" || "$need_sbt" || "$need_docker"; then
  if [[ "$platform" == Linux ]]; then
    [[ -f /etc/os-release ]] || fail "Cannot identify Linux. Install prerequisites manually."
    # The host distribution supplies this file, not the repository.
    # shellcheck disable=SC1091
    distro_id="$(. /etc/os-release; printf '%s' "$ID")"
    # shellcheck disable=SC1091
    distro_version="$(. /etc/os-release; printf '%s' "$VERSION_ID")"
    # shellcheck disable=SC1091
    distro_codename="$(. /etc/os-release; printf '%s' "${VERSION_CODENAME:-}")"
    case "$distro_id:$distro_version" in
      ubuntu:22.04|ubuntu:24.04|ubuntu:26.04|debian:12|debian:13) ;;
      *) fail "Automatic installation supports Ubuntu 22.04/24.04/26.04 and Debian 12/13. Other distributions: install JDK 17+, sbt, Docker Compose, and Buildx manually." ;;
    esac
  fi

  echo
  echo "Install only missing prerequisites; existing Docker installations and shell profiles are preserved."
  "$need_java" && echo "  JDK: Temurin 21 via Homebrew on macOS; OpenJDK via apt on Linux."
  "$need_sbt" && echo "  sbt 2.0.7: official checksum-verified release under /usr/local/share/typelevel-workshop."
  "$need_docker" && echo "  Docker: Desktop via Homebrew on macOS; Engine + Compose + Buildx via Docker's apt repository on Linux."
  echo "Downloads and administrator access may be required. Docker Desktop terms must be accepted in its app."
  confirm "Proceed with these installations?" || { echo "Cancelled. Nothing installed."; exit 1; }

  setup_directory="$(mktemp -d "${TMPDIR:-/tmp}/typelevel-setup.XXXXXX")"
  # This private directory is created above; no user directory is a cleanup target.
  trap 'rm -rf -- "$setup_directory"' EXIT
  trap 'echo "Setup failed. Review the error above; existing application data was not reset." >&2' ERR

  if [[ "$platform" == Linux ]]; then
    sudo apt-get update
    sudo apt-get install -y ca-certificates curl
    if "$need_java"; then
      java_package=default-jdk
      [[ "$distro_id:$distro_version" != ubuntu:22.04 ]] || java_package=openjdk-17-jdk
      sudo apt-get install -y "$java_package"
    fi
  elif "$need_java" || "$need_docker"; then
    brew_command="$(command -v brew || true)"
    if [[ -z "$brew_command" ]]; then
      for candidate in /opt/homebrew/bin/brew /usr/local/bin/brew; do
        if [[ -x "$candidate" ]]; then brew_command="$candidate"; break; fi
      done
    fi
    if [[ -z "$brew_command" ]]; then
      echo "Homebrew is also needed: https://brew.sh/"
      confirm "Download and run the official interactive Homebrew installer?" || exit 1
      curl --fail --show-error --silent --location --proto '=https' --tlsv1.2 --connect-timeout 15 --max-time 300 \
        https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh -o "$setup_directory/homebrew.sh"
      /bin/bash "$setup_directory/homebrew.sh"
      for candidate in /opt/homebrew/bin/brew /usr/local/bin/brew; do
        if [[ -x "$candidate" ]]; then brew_command="$candidate"; break; fi
      done
      [[ -n "$brew_command" ]] || fail "Homebrew setup is incomplete. Follow its instructions, then rerun this script."
    fi
    "$need_java" && "$brew_command" install --cask temurin@21
    "$need_docker" && "$brew_command" install --cask docker-desktop
  fi

  if "$need_sbt"; then
    sbt_directory=/usr/local/share/typelevel-workshop/sbt-2.0.7
    [[ ! -e /usr/local/bin/sbt && ! -L /usr/local/bin/sbt ]] ||
      fail "/usr/local/bin/sbt already exists. Fix its PATH/permissions instead of replacing it."
    [[ ! -e "$sbt_directory" ]] || fail "$sbt_directory already exists. Inspect the previous installation before retrying."
    curl --fail --show-error --silent --location --proto '=https' --tlsv1.2 --connect-timeout 15 --max-time 300 \
      https://github.com/sbt/sbt/releases/download/v2.0.7/sbt-2.0.7.tgz -o "$setup_directory/sbt.tgz"
    if has sha256sum; then
      digest="$(sha256sum "$setup_directory/sbt.tgz")"
    else
      digest="$(shasum -a 256 "$setup_directory/sbt.tgz")"
    fi
    [[ "${digest%% *}" == 439451520724253bbf22f3a34b0bad9379f18effb65a11755cd8bf705b7c202f ]] ||
      fail "sbt checksum verification failed; archive was not extracted."
    tar -xzf "$setup_directory/sbt.tgz" -C "$setup_directory"
    sudo install -d -m 0755 /usr/local/share/typelevel-workshop /usr/local/bin
    # Create exclusively; never merge an archive into an existing installation.
    sudo mkdir "$sbt_directory"
    sudo cp -R "$setup_directory/sbt/." "$sbt_directory/"
    sudo ln -s "$sbt_directory/bin/sbt" /usr/local/bin/sbt
  fi

  if [[ "$platform" == Linux ]] && "$need_docker"; then
    for package in docker.io docker-compose docker-compose-v2 docker-doc docker-buildx podman-docker containerd runc; do
      if [[ "$(dpkg-query -W -f='${Status}' "$package" 2>/dev/null || true)" == "install ok installed" ]]; then
        fail "Conflicting package $package is installed. Resolve it using https://docs.docker.com/engine/install/$distro_id/; this script will not uninstall it."
      fi
    done
    # Reuse a working repository, not a commented-out or disabled source file.
    docker_candidate="$(apt-cache policy docker-ce | awk '/Candidate:/ { print $2; exit }')"
    if [[ -z "$docker_candidate" || "$docker_candidate" == '(none)' ]]; then
      [[ "$distro_codename" =~ ^[a-z]+$ ]] || fail "Cannot identify the Docker repository codename."
      repository=/etc/apt/sources.list.d/typelevel-docker.sources
      keyring=/etc/apt/keyrings/typelevel-docker.asc
      [[ ! -e "$repository" && ! -e "$keyring" ]] || fail "Workshop Docker repository files already exist. Inspect them before retrying."
      curl --fail --show-error --silent --location --proto '=https' --tlsv1.2 --connect-timeout 15 --max-time 300 \
        "https://download.docker.com/linux/$distro_id/gpg" -o "$setup_directory/docker.asc"
      sudo install -d -m 0755 /etc/apt/keyrings
      sudo install -m 0644 "$setup_directory/docker.asc" "$keyring"
      printf 'Types: deb\nURIs: https://download.docker.com/linux/%s\nSuites: %s\nComponents: stable\nArchitectures: %s\nSigned-By: %s\n' \
        "$distro_id" "$distro_codename" "$(dpkg --print-architecture)" "$keyring" |
        sudo tee "$repository" >/dev/null
    fi
    sudo apt-get update
    sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    echo "Docker group membership gives root-equivalent access to this machine."
    if confirm "Add your user to the docker group so the startup script can run Docker without sudo?"; then
      sudo usermod -aG docker "$(id -un)"
      echo "Log out and log back in before starting the workshop."
    fi
  fi
fi

echo
if "$is_wsl"; then
  echo "Start Docker Desktop on Windows and enable Settings > Resources > WSL Integration for this distribution."
elif [[ "$platform" == Darwin ]]; then
  echo "Open Docker Desktop, review its terms, and finish first-time setup."
else
  echo "If Docker is stopped, start it with sudo systemctl start docker."
  echo "If access is denied, review https://docs.docker.com/engine/install/linux-postinstall/ and log in again after changing groups."
fi
echo "If Java or sbt is still missing, check JAVA_HOME and ensure /usr/local/bin is on PATH."
echo "For an existing Docker installation, install missing Compose/Buildx plugins using its provider's instructions; the engine was not replaced."
if report; then
  echo "Ready. Run ./scripts/start.sh from the repository root."
else
  echo "Setup needs the manual steps above. Then rerun bash scripts/setup.sh --check."
  exit 1
fi
