#!/usr/bin/env bash
# Roll a new build out to every ISP. Rebuilds the image once, backs up each
# tenant, then recreates each tenant's app container on the new image. Flyway
# runs any pending migrations (e.g. V18 traffic_usage) at startup.
#
#   ./deploy/update-all-tenants.sh
#   ./deploy/update-all-tenants.sh --no-build     # image already built
#   ./deploy/update-all-tenants.sh --skip-backup  # not recommended
#
# One tenant failing does not stop the others; the script exits non-zero if
# any failed, so cron/CI can alert.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TENANTS_DIR="$ROOT/deploy/tenants"
COMPOSE="$ROOT/deploy/tenant/docker-compose.yml"

say() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
die() { printf '\nError: %s\n' "$1" >&2; exit 1; }

BUILD=1
BACKUP=1
for arg in "$@"; do
  case "$arg" in
    --no-build)    BUILD=0 ;;
    --skip-backup) BACKUP=0 ;;
    *) die "unknown option: $arg (use --no-build, --skip-backup)" ;;
  esac
done

command -v docker >/dev/null || die "docker is not installed — run deploy/server-setup.sh first"

shopt -s nullglob
envs=("$TENANTS_DIR"/*.env)
if [ ${#envs[@]} -eq 0 ]; then
  die "no tenants found in $TENANTS_DIR — add one with deploy/new-tenant.sh"
fi
say "Found ${#envs[@]} tenant(s): $(for f in "${envs[@]}"; do basename "$f" .env; done | tr '\n' ' ')"

# --- Back up everyone before touching a live database ---
if [ "$BACKUP" -eq 1 ] && [ -x "$ROOT/deploy/backup.sh" ]; then
  say "Backing up all tenants first"
  "$ROOT/deploy/backup.sh" || die "backup failed — aborting before any upgrade"
elif [ "$BACKUP" -eq 1 ]; then
  printf '\nWarning: deploy/backup.sh not found or not executable — continuing without a backup.\n'
fi

# --- Rebuild the image once; every tenant then restarts onto it ---
if [ "$BUILD" -eq 1 ]; then
  say "Building spa-billing:latest"
  docker build -t spa-billing:latest "$ROOT" || die "image build failed — nothing was changed"
fi

# --- Roll each tenant onto the new image ---
failed=()
for f in "${envs[@]}"; do
  slug="$(basename "$f" .env)"
  say "Updating $slug"
  if docker compose -p "spa-$slug" --env-file "$f" -f "$COMPOSE" up -d; then
    printf '    %s updated\n' "$slug"
  else
    printf '    %s FAILED\n' "$slug"
    failed+=("$slug")
  fi
done

# --- Pick up any new site blocks and drop now-dangling old images ---
if docker ps --format '{{.Names}}' | grep -q '^spa-edge-caddy-1$'; then
  docker exec spa-edge-caddy-1 caddy reload --config /etc/caddy/Caddyfile >/dev/null 2>&1 \
    || printf '\nNote: edge proxy reload failed — check docker logs spa-edge-caddy-1.\n'
fi
docker image prune -f >/dev/null 2>&1 || true

if [ ${#failed[@]} -gt 0 ]; then
  printf '\n\033[1mDone with errors — failed: %s\033[0m\n' "${failed[*]}"
  printf 'Check: docker logs spa-<slug>-app-1\n'
  exit 1
fi
say "All tenants updated."
