#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$(cd -- "$script_directory/.." && pwd)"
schema_file="$project_directory/infrastructure/postgres/init/01-schema.sql"

cd "$project_directory"

wal_level="$(docker compose exec -T postgres psql -U postgres -d identity -Atc 'SHOW wal_level')"
if [[ "$wal_level" != "logical" ]]; then
  echo "Postgres needs wal_level=logical. Recreate the postgres service using the updated Compose configuration first; keep its data volume." >&2
  exit 1
fi


awk '
  /^-- Identity outbox$/ { outbox_starts++; copying = 1 }
  /^-- Identity CDC$/ { cdc_starts++; copying = 1 }
  copying { statements = statements $0 "\n" }
  /^-- End Identity outbox$/ { outbox_ends++; copying = 0 }
  /^-- End Identity CDC$/ { cdc_ends++; copying = 0 }
  END {
    if (outbox_starts != 1 || outbox_ends != 1 || cdc_starts != 1 || cdc_ends != 1 || copying) {
      print "Expected complete Identity outbox and CDC sections in init SQL" > "/dev/stderr"
      exit 1
    }
    printf "SET ROLE identity;\n%s", statements
  }
' "$schema_file" |
  docker compose exec -T postgres psql -U postgres -d identity \
    --single-transaction --set=ON_ERROR_STOP=1 -f -

echo "Identity outbox and Debezium publication are ready. Existing users were not backfilled."
