#!/usr/bin/env bash
set -euo pipefail

fail() { echo "Video seeding failed: $*" >&2; exit 1; }

case "${SEED_VIDEOS:-true}" in
  [Tt][Rr][Uu][Ee]) ;;
  [Ff][Aa][Ll][Ss][Ee]) echo "Video seeding disabled (SEED_VIDEOS=false)"; exit 0 ;;
  *) fail "SEED_VIDEOS must be true or false" ;;
esac

: "${AWS_ACCESS_KEY_ID:?AWS_ACCESS_KEY_ID must be configured}"
: "${AWS_SECRET_ACCESS_KEY:?AWS_SECRET_ACCESS_KEY must be configured}"

endpoint=${S3_ENDPOINT:-http://seaweedfs:8333}
bucket=${S3_BUCKET:-videos}
region=${AWS_REGION:-us-east-1}
case "$endpoint" in
  http://*|https://*) authority=${endpoint#*://}; authority=${authority%/} ;;
  *) fail "S3_ENDPOINT must be an HTTP(S) origin without credentials" ;;
esac
[[ -n "$authority" && "$authority" != *[/@?#[:space:]]* ]] ||
  fail "S3_ENDPOINT must be an HTTP(S) origin without credentials"
[[ "$bucket" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || fail "Invalid S3_BUCKET name"

export AWS_EC2_METADATA_DISABLED=true
aws_call() {
  local timeout_seconds=$1
  shift
  timeout "$timeout_seconds" aws --endpoint-url "$endpoint" --region "$region" \
    --no-cli-pager --cli-connect-timeout 5 --cli-read-timeout 20 "$@"
}

aws_call 15 s3api head-bucket --bucket "$bucket" >/dev/null ||
  fail "Cannot access the SeaweedFS seed bucket"

stat_object() {
  local result
  if result=$(aws_call 15 s3api head-object --bucket "$bucket" --key "$1" \
      --query ContentLength --output text 2>&1); then
    [[ "$result" =~ ^[0-9]+$ ]] || fail "Unexpected object size for $1"
    object_size=$result
    return 0
  fi
  [[ "$result" == *"(404)"* || "$result" == *"Not Found"* ]] ||
    fail "Cannot inspect the existing video; check SeaweedFS connectivity and permissions"
  return 1
}

upload_video() {
  local course_id=$1 slug=$2 source key size file_header
  source="/videos/$slug.mp4"
  key="courses/$course_id/lesson-1.mp4"

  if stat_object "$key"; then
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
  if aws_call 180 s3api put-object --bucket "$bucket" --key "$key" \
    --body "$source" --content-type video/mp4 --if-none-match '*' >/dev/null; then
    stat_object "$key" || fail "Cannot verify the upload of $slug"
    (( object_size == size )) || fail "Uploaded size does not match $slug"
  else
    stat_object "$key" || fail "Upload failed for $slug"
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
