#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$(cd -- "$script_directory/.." && pwd)"
schema_file="$project_directory/infrastructure/postgres/init/01-schema.sql"

cd "$project_directory"

wal_level="$(docker compose exec -T postgres psql -U postgres -d catalog -Atc 'SHOW wal_level')"
if [[ "$wal_level" != "logical" ]]; then
  echo "Postgres needs wal_level=logical. Recreate the postgres service using the updated Compose configuration first; keep its data volume." >&2
  exit 1
fi

awk '
  /^-- Catalog outbox$/ { outbox_starts++; copying = 1 }
  /^-- Catalog CDC$/ { cdc_starts++; copying = 1 }
  /^-- Catalog outbox seed$/ { seed_starts++; copying = 1; statements = statements "SET ROLE catalog;\n" }
  copying { statements = statements $0 "\n" }
  /^-- End Catalog outbox$/ { outbox_ends++; copying = 0 }
  /^-- End Catalog CDC$/ { cdc_ends++; copying = 0 }
  /^-- End Catalog outbox seed$/ { seed_ends++; copying = 0 }
  END {
    if (outbox_starts != 1 || outbox_ends != 1 || cdc_starts != 1 || cdc_ends != 1 || seed_starts != 1 || seed_ends != 1 || copying) {
      print "Expected complete Catalog outbox, CDC, and outbox seed sections in init SQL" > "/dev/stderr"
      exit 1
    }
    printf "SET ROLE catalog;\n%s", statements
  }
' "$schema_file" |
  docker compose exec -T postgres psql -U postgres -d catalog \
    --single-transaction --set=ON_ERROR_STOP=1 -f -

echo "Catalog outbox and Debezium publication are ready. Missing lesson events were backfilled; existing events were preserved."
