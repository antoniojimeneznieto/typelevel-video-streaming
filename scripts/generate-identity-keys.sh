#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$(cd -- "$script_directory/.." && pwd)"
source "$script_directory/images.sh"
key_directory="${IDENTITY_KEY_DIRECTORY:-$project_directory/infrastructure/identity/keys}"
private_key="$key_directory/private-key.pem"
public_key="$key_directory/public-key.pem"
key_image="$IMAGE_PREFIX/identity-service:$IMAGE_TAG"
private_temporary=""
public_temporary=""
normalized_temporary=""

cleanup() {
  for temporary_file in "$private_temporary" "$public_temporary" "$normalized_temporary"; do
    if [[ -n "$temporary_file" ]]; then
      rm -f "$temporary_file"
    fi
  done
}
trap cleanup EXIT

mkdir -p "$key_directory"

for key_path in "$private_key" "$public_key"; do
  if [[ -e "$key_path" && ! -f "$key_path" ]]; then
    echo "Expected a regular file or a missing path: $key_path" >&2
    exit 1
  fi
done

if ! docker image inspect "$key_image" >/dev/null 2>&1; then
  echo "Identity service image not found: $key_image. Pull or build it before generating keys." >&2
  exit 1
fi

openssl_in_container() {
  docker run --rm --interactive --entrypoint openssl "$key_image" "$@"
}

if [[ ! -f "$private_key" ]]; then
  private_temporary="$(mktemp "$key_directory/.identity-private-key.XXXXXX")"
  public_temporary="$(mktemp "$key_directory/.identity-public-key.XXXXXX")"

  openssl_in_container genpkey \
    -quiet \
    -algorithm RSA \
    -pkeyopt rsa_keygen_bits:2048 \
    > "$private_temporary"
  openssl_in_container pkey -pubout \
    < "$private_temporary" \
    > "$public_temporary"

  chmod 600 "$private_temporary"
  chmod 644 "$public_temporary"
  mv "$public_temporary" "$public_key"
  public_temporary=""
  mv "$private_temporary" "$private_key"
  private_temporary=""
  echo "Generated a new Identity RSA key pair."
elif [[ ! -f "$public_key" ]]; then
  public_temporary="$(mktemp "$key_directory/.identity-public-key.XXXXXX")"
  openssl_in_container pkey -pubout \
    < "$private_key" \
    > "$public_temporary"

  chmod 644 "$public_temporary"
  mv "$public_temporary" "$public_key"
  public_temporary=""
  chmod 600 "$private_key"
  echo "Derived the missing Identity public key from the private key."
else
  public_temporary="$(mktemp "$key_directory/.identity-public-key.XXXXXX")"
  normalized_temporary="$(mktemp "$key_directory/.identity-normalized-key.XXXXXX")"

  openssl_in_container pkey -pubout \
    < "$private_key" \
    > "$public_temporary"
  openssl_in_container pkey -pubin -pubout \
    < "$public_key" \
    > "$normalized_temporary"

  if ! cmp -s "$public_temporary" "$normalized_temporary"; then
    echo "The Identity private and public keys do not match." >&2
    exit 1
  fi

  chmod 600 "$private_key"
  chmod 644 "$public_key"
fi
