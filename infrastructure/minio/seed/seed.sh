#!/usr/bin/env bash
set -euo pipefail

fail() { echo "Video seeding failed: $*" >&2; exit 1; }

case "${SEED_VIDEOS:-true}" in
  [Tt][Rr][Uu][Ee]) ;;
  [Ff][Aa][Ll][Ss][Ee]) echo "Video seeding disabled (SEED_VIDEOS=false)"; exit 0 ;;
  *) fail "SEED_VIDEOS must be true or false" ;;
esac

: "${MINIO_ROOT_USER:?MINIO_ROOT_USER must be configured}"
: "${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD must be configured}"

endpoint=${MINIO_ENDPOINT:-http://minio:9000}
bucket=${S3_BUCKET:-videos}
region=${AWS_REGION:-us-east-1}
case "$endpoint" in
  http://*|https://*) authority=${endpoint#*://}; authority=${authority%/} ;;
  *) fail "MINIO_ENDPOINT must be an HTTP(S) origin without credentials" ;;
esac
[[ -n "$authority" && "$authority" != *[/@?#[:space:]]* ]] ||
  fail "MINIO_ENDPOINT must be an HTTP(S) origin without credentials"
[[ "$bucket" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || fail "Invalid S3_BUCKET name"

umask 077
MC_CONFIG_DIR=$(mktemp -d)
export MC_CONFIG_DIR MC_DISABLE_PAGER=1

mc_call() { timeout "$1" mc --json --no-color "${@:2}" 2>/dev/null; }

mc_call 15 alias set seed "$endpoint" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" \
  --api S3v4 --path on >/dev/null || fail "Cannot connect to MinIO"
mc_call 15 mb --ignore-existing --region "$region" "seed/$bucket" >/dev/null ||
  fail "Cannot create or access the seed bucket"
policy=$(mc_call 15 anonymous get-json "seed/$bucket") || fail "Cannot inspect bucket access"
[[ "$policy" =~ \"permission\":[[:space:]]*\"private\" ]] ||
  fail "Use a private bucket without a bucket policy; existing policy was not changed"

stat_object() {
  local result
  if result=$(mc_call 15 stat --no-list "$1"); then
    [[ "$result" =~ \"type\":[[:space:]]*\"file\" &&
       "$result" =~ \"size\":[[:space:]]*([0-9]+) ]] || fail "Unexpected object metadata"
    object_size=${BASH_REMATCH[1]}
    return 0
  fi
  [[ "$result" =~ \"message\":[[:space:]]*\"Object\ does\ not\ exist\" ]] ||
    fail "Cannot inspect the existing video; check MinIO connectivity and permissions"
  return 1
}

upload_video() {
  local course_id=$1 slug=$2 source target size file_header
  source="/videos/$slug.mp4"
  target="seed/$bucket/courses/$course_id/lesson-1.mp4"

  if stat_object "$target"; then
    (( object_size > 0 )) || fail "Existing object for $slug is empty; inspect it before retrying"
    echo "Already uploaded: $slug; leaving existing object unchanged"
    return
  fi

  [[ -f "$source" && -s "$source" ]] || fail "Missing or empty local MP4: $source"
  IFS= read -r -n 128 file_header < "$source" || true
  [[ "$file_header" != 'version https://git-lfs.github.com/spec/v1'* ]] ||
    fail "$slug.mp4 is a Git LFS pointer; run git lfs pull first"
  size=$(stat -c %s "$source")
  (( size <= 5 * 1024 * 1024 * 1024 )) || fail "$slug exceeds the 5 GiB single-upload limit"

  echo "Uploading $slug"
  if mc_call 180 --custom-header 'If-None-Match:*' cp --disable-multipart \
    --attr 'Content-Type=video/mp4' "$source" "$target" >/dev/null; then
    stat_object "$target" || fail "Cannot verify the upload of $slug"
    (( object_size == size )) || fail "Uploaded size does not match $slug"
  else
    stat_object "$target" || fail "Upload failed for $slug"
    (( object_size > 0 )) || fail "Existing object for $slug is empty; inspect it before retrying"
    echo "Already uploaded concurrently: $slug; leaving it unchanged"
  fi
}

upload_video 00000000-0000-0000-0000-000000000104 threads-at-scale
upload_video 00000000-0000-0000-0000-000000000101 typelevel-retrospective
upload_video 00000000-0000-0000-0000-000000000106 rethinking-monad-transformers
upload_video 00000000-0000-0000-0000-000000000105 fs2-chunk
upload_video 00000000-0000-0000-0000-000000000102 cats-effect-3

echo "Video seeding completed"
