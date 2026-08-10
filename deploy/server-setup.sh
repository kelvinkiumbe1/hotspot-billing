#!/usr/bin/env bash
# One-time server bootstrap for a fresh Ubuntu VPS (22.04 or 24.04), on any
# provider — Hetzner, DigitalOcean, Vultr, a Kenyan host, whatever. Installs
# Docker, opens the firewall, builds the app image and starts the shared edge
# proxy, leaving the box ready for ./deploy/new-tenant.sh.
#
#   git clone <your repo> /srv/spa-billing
#   cd /srv/spa-billing
#   sudo ./deploy/server-setup.sh
#
# Safe to re-run: every step checks whether it is already done.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

say()  { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
die()  { printf '\nError: %s\n' "$1" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root (sudo ./deploy/server-setup.sh)"
[ -f /etc/debian_version ] || die "this script targets Ubuntu/Debian; install Docker manually elsewhere"

# --- Docker Engine + Compose plugin (official apt repository) ---
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  say "Docker already installed ($(docker --version))"
else
  say "Installing Docker Engine and the Compose plugin"
  apt-get update -y
  apt-get install -y ca-certificates curl gnupg
  install -m 0755 -d /etc/apt/keyrings
  if [ ! -f /etc/apt/keyrings/docker.gpg ]; then
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
      | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
  fi
  # shellcheck disable=SC1091
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker
fi

# --- Firewall: SSH, HTTP, HTTPS. HTTP (80) is needed for Let's Encrypt. ---
if command -v ufw >/dev/null 2>&1; then
  say "Opening the firewall (22, 80, 443)"
  # Allow SSH *before* enabling, so a fresh box can't lock you out.
  ufw allow OpenSSH >/dev/null 2>&1 || ufw allow 22/tcp >/dev/null
  ufw allow 80/tcp  >/dev/null
  ufw allow 443/tcp >/dev/null
  if ! ufw status | grep -q "Status: active"; then
    ufw --force enable
  fi
  ufw status verbose | sed 's/^/    /'
else
  say "ufw not present — skipping firewall. Make sure 22, 80 and 443 are open."
fi

# --- Shared edge network ---
if docker network inspect spa-edge >/dev/null 2>&1; then
  say "Edge network 'spa-edge' already exists"
else
  say "Creating the shared edge network 'spa-edge'"
  docker network create spa-edge >/dev/null
fi

# --- Build the application image ---
say "Building spa-billing:latest (first build takes a few minutes)"
docker build -t spa-billing:latest "$ROOT"

# --- Start the edge proxy ---
if docker ps --format '{{.Names}}' | grep -q '^spa-edge-caddy-1$'; then
  say "Edge proxy already running"
else
  say "Starting the edge proxy (Caddy — issues HTTPS automatically)"
  docker compose -f "$ROOT/deploy/edge/docker-compose.yml" up -d
fi

cat <<EOF

--------------------------------------------------------------------
Server is ready.

Next:
  1. Point DNS at this server. A wildcard is easiest so new ISPs need
     no DNS work:
        *.yourdomain.co.ke.   A   <this server's public IP>
  2. Put your own email in deploy/edge/Caddyfile (cert-expiry notices).
  3. Add your first ISP:
        ./deploy/new-tenant.sh acme acme.yourdomain.co.ke
  4. Bridge to each ISP's MikroTik (WireGuard) — see deploy/README.md.
  5. Before telling a tenant they're live:
        ./deploy/preflight.sh acme
--------------------------------------------------------------------
EOF
