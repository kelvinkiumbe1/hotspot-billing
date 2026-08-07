#!/usr/bin/env bash
# Checks a tenant is genuinely ready to take real money before you tell
# them it is. Every failure here is something that looks fine from the
# outside: the app loads, the admin works, and nothing collects.
#
#   ./deploy/preflight.sh acme
#
# Exits non-zero if anything would stop them trading, so it can gate a
# release rather than being read and forgotten.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SLUG="${1:-}"
[ -n "$SLUG" ] || { echo "usage: $0 <slug>"; exit 2; }

ENV_FILE="$ROOT/deploy/tenants/$SLUG.env"
[ -f "$ENV_FILE" ] || { echo "No such tenant: $ENV_FILE"; exit 2; }

# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a

fail=0
warn=0
pass() { printf '  \033[32mok\033[0m    %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; fail=$((fail + 1)); }
soft() { printf '  \033[33mwarn\033[0m  %s\n' "$1"; warn=$((warn + 1)); }

echo
echo "Pre-flight for $SLUG ($TENANT_DOMAIN)"
echo

# --- Money -----------------------------------------------------------
echo "M-Pesa"
if [ "${MPESA_BASE_URL:-}" = "https://api.safaricom.co.ke" ]; then
  pass "pointed at production Daraja"
else
  bad "MPESA_BASE_URL is '${MPESA_BASE_URL:-unset}' — on sandbox it takes no real money"
fi

for v in MPESA_CONSUMER_KEY MPESA_CONSUMER_SECRET MPESA_SHORT_CODE MPESA_PASSKEY; do
  if [ -n "${!v:-}" ]; then pass "$v set"; else bad "$v is empty — STK push will fail"; fi
done

if [ -n "${MPESA_PAYBILL:-}" ]; then
  pass "paybill $MPESA_PAYBILL shown to customers"
else
  soft "MPESA_PAYBILL empty — customers will not be told where to pay manually"
fi

# --- Access ----------------------------------------------------------
echo
echo "Access"
case "${ADMIN_PASSWORD:-}" in
  admin123|password|admin|changeme|"")
    bad "ADMIN_PASSWORD is a default — anyone who has seen this project knows it" ;;
  *)
    if [ "${#ADMIN_PASSWORD}" -lt 12 ]; then
      soft "ADMIN_PASSWORD is under 12 characters"
    else
      pass "break-glass password looks deliberate"
    fi ;;
esac
[ "${TECH_PASSWORD:-}" = "tech123" ] && bad "TECH_PASSWORD is the default" || pass "technician password changed"

# --- Reachability ----------------------------------------------------
echo
echo "Reachability"
if command -v getent >/dev/null 2>&1 && getent hosts "$TENANT_DOMAIN" >/dev/null 2>&1; then
  pass "$TENANT_DOMAIN resolves"
else
  bad "$TENANT_DOMAIN does not resolve — no certificate will be issued"
fi

if curl -sf -m 10 "https://$TENANT_DOMAIN/api/plans" >/dev/null 2>&1; then
  pass "https://$TENANT_DOMAIN is serving over TLS"
else
  bad "https://$TENANT_DOMAIN/api/plans did not answer"
fi

# Safaricom must be able to reach the callback from the public internet.
CB="https://$TENANT_DOMAIN/api/payments/mpesa/callback"
code=$(curl -s -o /dev/null -w '%{http_code}' -m 10 -X POST "$CB" \
        -H 'Content-Type: application/json' -d '{}' 2>/dev/null)
if [ "$code" = "000" ]; then
  bad "the M-Pesa callback is unreachable — payments will never be confirmed"
else
  pass "callback reachable (HTTP $code) at $CB"
fi

# --- Containers ------------------------------------------------------
echo
echo "Containers"
for c in "spa-$SLUG-db-1" "spa-$SLUG-app-1"; do
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$c"; then
    health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$c" 2>/dev/null)
    if [ "$health" = "unhealthy" ]; then
      bad "$c is running but unhealthy"
    else
      pass "$c up${health:+ ($health)}"
    fi
  else
    bad "$c is not running"
  fi
done

# --- Router ----------------------------------------------------------
echo
echo "Router"
routers=$(docker exec "spa-$SLUG-db-1" psql -tAU "$DB_USERNAME" -d "$DB_NAME" \
          -c "select count(*) from routers" 2>/dev/null | tr -d '[:space:]')
if [ -n "$routers" ] && [ "$routers" -gt 0 ] 2>/dev/null; then
  pass "$routers router(s) configured"
else
  bad "no router configured — the app will bill correctly but switch nobody on"
fi

# --- Backups ---------------------------------------------------------
echo
echo "Backups"
if crontab -l 2>/dev/null | grep -q 'backup.sh'; then
  pass "nightly backup is in cron"
else
  soft "no backup cron entry found — see deploy/README.md"
fi
if [ -d "$ROOT/backups" ] && [ -n "$(find "$ROOT/backups" -name 'database.sql.gz' -mtime -2 2>/dev/null)" ]; then
  pass "a backup was taken in the last 2 days"
else
  soft "no recent backup on disk"
fi

echo
if [ "$fail" -gt 0 ]; then
  echo "NOT READY — $fail blocking issue(s), $warn warning(s)."
  echo "Fix the failures above before telling $SLUG they are live."
  exit 1
fi
echo "Ready to trade${warn:+ — $warn warning(s) worth a look}."
