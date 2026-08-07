#!/usr/bin/env bash
# Stands up one ISP: its own database, its own app container, its own
# subdomain with HTTPS, and its own M-Pesa and messaging credentials.
#
#   ./deploy/new-tenant.sh acme acme.yourdomain.co.ke
#
# Passwords are generated rather than chosen, and printed once. Re-running
# for an existing tenant is refused so a live database is never re-seeded.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TENANTS_DIR="$ROOT/deploy/tenants"
CADDY_DIR="$ROOT/deploy/edge/tenants.d"

die() { printf '\nError: %s\n' "$1" >&2; exit 1; }

[ $# -ge 2 ] || die "usage: $0 <slug> <domain>
  slug    short name, lowercase letters, digits and dashes (e.g. acme)
  domain  the subdomain this ISP will use (e.g. acme.yourdomain.co.ke)"

SLUG="$1"
DOMAIN="$2"

[[ "$SLUG" =~ ^[a-z0-9-]{2,20}$ ]] || die "slug must be 2-20 lowercase letters, digits or dashes"
[[ "$DOMAIN" =~ ^[a-z0-9.-]+\.[a-z]{2,}$ ]] || die "'$DOMAIN' does not look like a domain"

ENV_FILE="$TENANTS_DIR/$SLUG.env"
[ -f "$ENV_FILE" ] && die "$SLUG already exists ($ENV_FILE).
Editing that file and re-running 'docker compose ... up -d' is the way to change it."

command -v docker >/dev/null || die "docker is not installed"
docker compose version >/dev/null 2>&1 || die "the docker compose plugin is missing"

# openssl is not guaranteed on a minimal host, so fall back to urandom.
generate() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 24 | tr -d '/+=' | cut -c1-20
  else
    LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 20
  fi
}

mkdir -p "$TENANTS_DIR" "$CADDY_DIR"

DB_PASSWORD="$(generate)"
ADMIN_PASSWORD="$(generate)"
TECH_PASSWORD="$(generate)"

umask 077   # credentials must not be world-readable
cat > "$ENV_FILE" <<EOF
# $SLUG — generated $(date -u +%Y-%m-%dT%H:%M:%SZ). Contains secrets; keep it off git.
TENANT_SLUG=$SLUG
TENANT_DOMAIN=$DOMAIN
APP_IMAGE=spa-billing:latest

DB_NAME=spa_$(printf '%s' "$SLUG" | tr '-' '_')
DB_USERNAME=spa_$(printf '%s' "$SLUG" | tr '-' '_')
DB_PASSWORD=$DB_PASSWORD

ADMIN_USERNAME=admin
ADMIN_PASSWORD=$ADMIN_PASSWORD
TECH_USERNAME=technician
TECH_PASSWORD=$TECH_PASSWORD

# --- This ISP's own Daraja app. Leave blank until they hand these over;
# --- the app runs fine without them, it simply cannot collect by M-Pesa.
# --- MPESA_BASE_URL must become https://api.safaricom.co.ke to take real money.
MPESA_BASE_URL=https://sandbox.safaricom.co.ke
MPESA_CONSUMER_KEY=
MPESA_CONSUMER_SECRET=
MPESA_SHORT_CODE=
MPESA_PASSKEY=
MPESA_PAYBILL=

# --- This ISP's own messaging accounts, so the cost lands on them.
SMS_ENABLED=false
SMS_USERNAME=
SMS_API_KEY=
SMS_SENDER_ID=
WHATSAPP_ENABLED=false
WHATSAPP_PHONE_NUMBER_ID=
WHATSAPP_ACCESS_TOKEN=

ALERT_PHONE=
EOF

# Caddy resolves the container by name on the shared network. Compose names
# it <project>-app-1, and the project name is set in the compose file.
cat > "$CADDY_DIR/$SLUG.caddy" <<EOF
# $SLUG
$DOMAIN {
	import tenant_defaults
	reverse_proxy spa-$SLUG-app-1:8080
}
EOF

echo "Wrote $ENV_FILE and $CADDY_DIR/$SLUG.caddy"

if ! docker network inspect spa-edge >/dev/null 2>&1; then
  echo "Creating the shared edge network…"
  docker network create spa-edge >/dev/null
fi

if ! docker image inspect spa-billing:latest >/dev/null 2>&1; then
  echo "Building spa-billing:latest (first run takes a few minutes)…"
  docker build -t spa-billing:latest "$ROOT"
fi

echo "Starting $SLUG…"
docker compose -p "spa-$SLUG" --env-file "$ENV_FILE" \
  -f "$ROOT/deploy/tenant/docker-compose.yml" up -d

# Pick up the new site block. Reload rather than restart so other tenants
# keep serving and certificates are not re-requested.
if docker ps --format '{{.Names}}' | grep -q '^spa-edge-caddy-1$'; then
  echo "Reloading the edge proxy…"
  docker exec spa-edge-caddy-1 caddy reload --config /etc/caddy/Caddyfile >/dev/null \
    || echo "  Reload failed — check 'docker logs spa-edge-caddy-1'."
else
  echo "The edge proxy is not running yet. Start it with:"
  echo "  docker compose -f deploy/edge/docker-compose.yml up -d"
fi

cat <<EOF

--------------------------------------------------------------------
$SLUG is up at https://$DOMAIN

  Owner login     admin / $ADMIN_PASSWORD
  Technician      technician / $TECH_PASSWORD

Write these down now — they are not stored anywhere else in readable
form, and the passwords above are the only copy.

Before they can take money:
  1. Point $DOMAIN at this server's IP, or HTTPS will not be issued.
  2. Get their Daraja consumer key, secret, shortcode and passkey into
     $ENV_FILE, set MPESA_BASE_URL to https://api.safaricom.co.ke, then
     re-run the compose command above.
  3. Register this callback URL in their Daraja app:
     https://$DOMAIN/api/payments/mpesa/callback
  4. Set up the tunnel to their router — see deploy/README.md. Until
     then the app bills correctly but cannot switch anyone on.
--------------------------------------------------------------------
EOF
