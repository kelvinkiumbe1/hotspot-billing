#!/usr/bin/env bash
# Dumps every tenant's database, proves the dump can be restored, copies it off
# the machine, and reports the outcome back to the app so a night that never
# happened gets noticed.
#
#   ./deploy/backup.sh                    # all tenants
#   ./deploy/backup.sh acme               # just one
#   ./deploy/backup.sh --prune 30         # delete backups older than 30 days
#
# Meant for cron:
#   0 2 * * *  cd /srv/spa-billing && ./deploy/backup.sh >> /var/log/spa-backup.log 2>&1
#
# Per-tenant settings, read from deploy/tenants/<slug>.env:
#   BACKUP_REMOTE        rclone remote ("s3:bucket/path") or scp target
#                        ("user@host:/path"). Blank = no off-site copy, which
#                        the app will report as a backup you cannot rely on.
#   BACKUP_VERIFY        1 (default) restores the dump into a scratch database
#                        to prove it reads; 0 to skip on a very small server.
#   BACKUP_REPORT_TOKEN  an API token (Settings -> API tokens) so this script
#                        can report the run. Blank = no reporting, and the app
#                        will eventually alert that backups have gone quiet.

set -uo pipefail

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

# Tells the app how the run went. Silence here is what the app alerts on, so
# a failure must still be reported — that is the whole point.
report() {
  local slug="$1" ok="$2" bytes="$3" verified="$4" offsite="$5" ms="$6" err="$7"
  [ -n "${TENANT_DOMAIN:-}" ] || return 0
  [ -n "${BACKUP_REPORT_TOKEN:-}" ] || return 0
  local escaped
  escaped=$(printf '%s' "$err" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr -d '\n')
  curl -fsS -m 20 -X POST "https://$TENANT_DOMAIN/api/admin/ops/backup-report" \
    -H "Authorization: Bearer $BACKUP_REPORT_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"tenant\":\"$slug\",\"ok\":$ok,\"bytes\":$bytes,\"verified\":$verified,\"offsite\":$offsite,\"durationMs\":$ms,\"error\":\"$escaped\"}" \
    >/dev/null 2>&1 \
    || echo "  $slug: could not report the backup to the app"
}

failed=0
for env_file in "${FILES[@]}"; do
  slug="$(basename "$env_file" .env)"
  [ -n "$ONLY" ] && [ "$ONLY" != "$slug" ] && continue

  # Each tenant's variables, cleared between tenants so one blank setting
  # cannot silently inherit the previous tenant's value.
  unset BACKUP_REMOTE BACKUP_REPORT_TOKEN TENANT_DOMAIN BACKUP_VERIFY
  # shellcheck disable=SC1090
  set -a; . "$env_file"; set +a

  started=$(date +%s%3N 2>/dev/null || echo 0)
  out="$BACKUP_ROOT/$STAMP/$slug"
  mkdir -p "$out"
  dump="$out/database.sql.gz"
  bytes=0
  verified=false
  offsite=false
  error=""

  db_container="spa-$slug-db-1"
  app_container="spa-$slug-app-1"

  if ! docker ps --format '{{.Names}}' | grep -qx "$db_container"; then
    echo "  $slug: database container is not running — SKIPPED"
    report "$slug" false 0 false false 0 "database container $db_container is not running"
    failed=1
    continue
  fi

  # pg_dump's own exit status, not gzip's. Testing the tail of a pipe is how a
  # truncated dump passes for a good one: gzip happily compresses a stream that
  # died halfway and exits 0.
  docker exec "$db_container" pg_dump -U "$DB_USERNAME" -d "$DB_NAME" --clean | gzip > "$dump"
  dump_status=${PIPESTATUS[0]}
  if [ "$dump_status" -ne 0 ]; then
    echo "  $slug: pg_dump FAILED (exit $dump_status)"
    rm -f "$dump"
    report "$slug" false 0 false false 0 "pg_dump exited $dump_status"
    failed=1
    continue
  fi

  # An archive that will not even decompress is not a backup.
  if ! gzip -t "$dump" 2>/dev/null; then
    echo "  $slug: dump is corrupt — gzip cannot read it back"
    report "$slug" false 0 false false 0 "gzip integrity check failed"
    failed=1
    continue
  fi
  bytes=$(wc -c < "$dump" | tr -d ' ')
  echo "  $slug: database dumped ($(du -h "$dump" | cut -f1))"

  # Restore it into a scratch database. A dump that reads is the only kind
  # worth having, and the only way to know is to actually restore one.
  if [ "${BACKUP_VERIFY:-1}" = "1" ]; then
    scratch="${DB_NAME}_verify"
    docker exec "$db_container" psql -U "$DB_USERNAME" -d postgres -q \
      -c "DROP DATABASE IF EXISTS $scratch;" >/dev/null 2>&1
    if docker exec "$db_container" psql -U "$DB_USERNAME" -d postgres -q \
        -c "CREATE DATABASE $scratch;" >/dev/null 2>&1; then
      # --clean dumps start with DROPs that fail on an empty database; those
      # errors are expected, so only the table count at the end is trusted.
      gunzip -c "$dump" | docker exec -i "$db_container" \
        psql -U "$DB_USERNAME" -d "$scratch" -q >/dev/null 2>&1
      tables=$(docker exec "$db_container" psql -U "$DB_USERNAME" -d "$scratch" -tAc \
        "select count(*) from information_schema.tables where table_schema='public'" 2>/dev/null | tr -d ' ')
      docker exec "$db_container" psql -U "$DB_USERNAME" -d postgres -q \
        -c "DROP DATABASE IF EXISTS $scratch;" >/dev/null 2>&1
      if [ "${tables:-0}" -ge 10 ]; then
        verified=true
        echo "  $slug: restore verified ($tables tables)"
      else
        error="restore produced only ${tables:-0} tables"
        echo "  $slug: RESTORE CHECK FAILED — $error"
        failed=1
      fi
    else
      error="could not create the scratch database for verification"
      echo "  $slug: $error"
    fi
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

  # Get it off this machine. A backup on the same disk as the database is not
  # a backup — it is a second copy of the thing that is about to fail.
  if [ -n "${BACKUP_REMOTE:-}" ]; then
    if command -v rclone >/dev/null 2>&1 && [[ "$BACKUP_REMOTE" == *:* && "$BACKUP_REMOTE" != *@* ]]; then
      if rclone copy "$out" "$BACKUP_REMOTE/$STAMP/$slug" >/dev/null 2>&1; then
        offsite=true
      fi
    elif command -v scp >/dev/null 2>&1; then
      if ssh "${BACKUP_REMOTE%%:*}" "mkdir -p '${BACKUP_REMOTE#*:}/$STAMP/$slug'" >/dev/null 2>&1 \
          && scp -q "$out"/* "${BACKUP_REMOTE}/$STAMP/$slug/" >/dev/null 2>&1; then
        offsite=true
      fi
    fi
    if [ "$offsite" = true ]; then
      echo "  $slug: copied off-site"
    else
      error="${error:+$error; }off-site copy to $BACKUP_REMOTE failed"
      echo "  $slug: OFF-SITE COPY FAILED"
      failed=1
    fi
  else
    echo "  $slug: no BACKUP_REMOTE set — this copy lives only on this machine"
  fi

  finished=$(date +%s%3N 2>/dev/null || echo 0)
  elapsed=$(( finished > started ? finished - started : 0 ))
  ok=true
  [ -n "$error" ] && ok=false
  report "$slug" "$ok" "$bytes" "$verified" "$offsite" "$elapsed" "$error"
done

echo "Backups in $BACKUP_ROOT/$STAMP"
[ "$failed" -eq 0 ] || { echo "One or more tenants did not back up cleanly."; exit 1; }
