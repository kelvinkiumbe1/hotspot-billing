#!/usr/bin/env bash
# Dumps every tenant's database and uploads to a dated folder.
#
#   ./deploy/backup.sh                    # all tenants
#   ./deploy/backup.sh acme               # just one
#   ./deploy/backup.sh --prune 30         # delete backups older than 30 days
#
# Meant for cron:
#   0 2 * * *  cd /srv/spa-billing && ./deploy/backup.sh >> /var/log/spa-backup.log 2>&1

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TENANTS_DIR="$ROOT/deploy/tenants"
BACKUP_ROOT="${BACKUP_DIR:-$ROOT/backups}"
STAMP="$(date -u +%Y-%m-%d_%H%M)"

if [ "${1:-}" = "--prune" ]; then
  DAYS="${2:-30}"
  echo "Removing backups older than $DAYS days from $BACKUP_ROOT"
  find "$BACKUP_ROOT" -maxdepth 1 -type d -mtime "+$DAYS" -exec rm -rf {} + 2>/dev/null || true
  exit 0
fi

ONLY="${1:-}"
shopt -s nullglob
FILES=("$TENANTS_DIR"/*.env)
[ ${#FILES[@]} -gt 0 ] || { echo "No tenants in $TENANTS_DIR"; exit 0; }

failed=0
for env_file in "${FILES[@]}"; do
  slug="$(basename "$env_file" .env)"
  [ -n "$ONLY" ] && [ "$ONLY" != "$slug" ] && continue

  # shellcheck disable=SC1090
  set -a; . "$env_file"; set +a

  out="$BACKUP_ROOT/$STAMP/$slug"
  mkdir -p "$out"

  db_container="spa-$slug-db-1"
  app_container="spa-$slug-app-1"

  if ! docker ps --format '{{.Names}}' | grep -qx "$db_container"; then
    echo "  $slug: database container is not running — SKIPPED"
    failed=1
    continue
  fi

  # Fail loudly on a bad dump rather than leaving a truncated file that
  # looks like a backup until the day you need it.
  if docker exec "$db_container" pg_dump -U "$DB_USERNAME" -d "$DB_NAME" --clean \
      | gzip > "$out/database.sql.gz"; then
    size=$(du -h "$out/database.sql.gz" | cut -f1)
    echo "  $slug: database dumped ($size)"
  else
    echo "  $slug: pg_dump FAILED"
    rm -f "$out/database.sql.gz"
    failed=1
    continue
  fi

  if docker ps --format '{{.Names}}' | grep -qx "$app_container"; then
    if docker exec "$app_container" sh -c 'cd /app && tar cf - uploads 2>/dev/null' \
        | gzip > "$out/uploads.tar.gz"; then
      echo "  $slug: uploads archived"
    else
      echo "  $slug: uploads archive failed (photos may be missing)"
      failed=1
    fi
  fi
done

echo "Backups in $BACKUP_ROOT/$STAMP"
[ "$failed" -eq 0 ] || { echo "One or more tenants did not back up cleanly."; exit 1; }
