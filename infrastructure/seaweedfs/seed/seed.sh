#!/bin/sh
set -eu

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
case "$authority" in
  ''|*[/@?#[:space:]]*) fail "S3_ENDPOINT must be an HTTP(S) origin without credentials" ;;
esac
case "$bucket" in
  ''|*[!a-z0-9.-]*|[!a-z0-9]*|*[!a-z0-9]) fail "Invalid S3_BUCKET name" ;;
esac
[ "${#bucket}" -ge 3 ] && [ "${#bucket}" -le 63 ] || fail "Invalid S3_BUCKET name"
bucket_url="${endpoint%/}/$bucket"

s3_call() {
  request_timeout=$1
  shift
  # Import credentials inside curl to keep their values out of process arguments.
  curl --disable --silent --show-error --globoff --proto '=http,https' \
    --connect-timeout 5 --max-time "$request_timeout" --speed-time 20 --speed-limit 1 \
    --aws-sigv4 "aws:amz:$region:s3" \
    --variable '%AWS_ACCESS_KEY_ID' --variable '%AWS_SECRET_ACCESS_KEY' \
    --expand-user '{{AWS_ACCESS_KEY_ID}}:{{AWS_SECRET_ACCESS_KEY}}' \
    --output /dev/null --write-out '%{http_code}' "$@"
}

bucket_status=$(s3_call 15 --head --url "$bucket_url") ||
  fail "Cannot access the SeaweedFS seed bucket"
[ "$bucket_status" = 200 ] || fail "Cannot access the SeaweedFS seed bucket (HTTP $bucket_status)"

stat_object() {
  head_result=$(s3_call 15 --head --write-out '%{http_code} %header{content-length}' \
    --url "$bucket_url/$1") ||
    fail "Cannot inspect the existing video; check SeaweedFS connectivity and permissions"
  case "${head_result%% *}" in
    200)
      object_size=${head_result#* }
      case "$object_size" in
        ''|*[!0-9]*) fail "Unexpected object size for $1" ;;
      esac
      return 0
      ;;
    404) return 1 ;;
    *) fail "Cannot inspect the existing video (HTTP ${head_result%% *}); check SeaweedFS connectivity and permissions" ;;
  esac
}

upload_video() {
  course_id=$1
  slug=$2
  source="/videos/$slug.mp4"
  key="courses/$course_id/lesson-1.mp4"

  if stat_object "$key"; then
    [ "$object_size" -gt 0 ] || fail "Existing object for $slug is empty; inspect it before retrying"
    echo "Already uploaded: $slug; leaving existing object unchanged"
    return
  fi

  [ -f "$source" ] && [ -s "$source" ] || fail "Missing or empty local MP4: $source"
  file_header=$(dd if="$source" bs=128 count=1 2>/dev/null | tr -d '\000')
  case "$file_header" in
    'version https://git-lfs.github.com/spec/v1'*) fail "$slug.mp4 is a Git LFS pointer; run git lfs pull first" ;;
  esac
  size=$(stat -c %s "$source") || fail "Cannot read the size of $slug"
  [ "$size" -le $((5 * 1024 * 1024 * 1024)) ] || fail "$slug exceeds the 5 GiB single-upload limit"
  # curl streams the file; provide its hash explicitly for signed payload verification.
  payload_hash=$(sha256sum "$source") || fail "Cannot hash $slug"
  payload_hash=${payload_hash%% *}

  echo "Uploading $slug"
  put_status=$(s3_call 180 --upload-file "$source" --url "$bucket_url/$key" \
    --header 'Content-Type: video/mp4' --header 'If-None-Match: *' \
    --header "x-amz-content-sha256: $payload_hash") ||
    fail "Upload failed for $slug; retry to check whether it completed"
  case "$put_status" in
    200)
      stat_object "$key" || fail "Cannot verify the upload of $slug"
      [ "$object_size" -eq "$size" ] || fail "Uploaded size does not match $slug"
      ;;
    412)
      stat_object "$key" || fail "Cannot verify the concurrent upload of $slug"
      [ "$object_size" -gt 0 ] || fail "Existing object for $slug is empty; inspect it before retrying"
      echo "Already uploaded concurrently: $slug; leaving it unchanged"
      ;;
    *) fail "Upload failed for $slug (HTTP $put_status)" ;;
  esac
}

upload_video 00000000-0000-0000-0000-000000000104 threads-at-scale
upload_video 00000000-0000-0000-0000-000000000101 typelevel-retrospective
upload_video 00000000-0000-0000-0000-000000000106 rethinking-monad-transformers
upload_video 00000000-0000-0000-0000-000000000105 fs2-chunk
upload_video 00000000-0000-0000-0000-000000000102 cats-effect-3

echo "Video seeding completed"
