#!/usr/bin/env bash

image_settings="$(docker compose --project-directory "$project_directory" config --environment |
  awk '/^(IMAGE_PREFIX|IMAGE_TAG|FRONTEND_PORT|S3_PUBLIC_ENDPOINT|LOCAL_UID|LOCAL_GID)=/')"
while IFS= read -r setting; do
  if [[ -n "$setting" ]]; then export "$setting"; fi
done <<< "$image_settings"

export IMAGE_PREFIX="${IMAGE_PREFIX:-ghcr.io/antoniojimeneznieto/typelevel-video-streaming}"
if [[ -z "${IMAGE_TAG:-}" ]]; then
  IMAGE_TAG="$(git -C "$project_directory" describe --tags --exact-match --match 'v[0-9]*' 2>/dev/null || true)"
  if [[ -z "$IMAGE_TAG" ]]; then
    IMAGE_TAG="sha-$(git -C "$project_directory" rev-parse HEAD)"
  fi
fi
export IMAGE_TAG
export LOCAL_UID="${LOCAL_UID:-$(id -u)}"
export LOCAL_GID="${LOCAL_GID:-$(id -g)}"

if [[ "${CODESPACES:-false}" == true && -z "${S3_PUBLIC_ENDPOINT:-}" ]]; then
  export S3_PUBLIC_ENDPOINT="https://${CODESPACE_NAME}-${FRONTEND_PORT:-8000}.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-app.github.dev}"
fi
