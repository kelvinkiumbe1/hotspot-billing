#!/usr/bin/env bash
# Stand up the WireGuard interface routers dial in to.
#
#   sudo ./deploy/vpn-setup.sh
#
# Why this exists: a site router on a mobile or domestic line has no address
# anything can dial in to. So the MikroTik API cannot be opened, a TR-069
# connection request cannot be delivered, and every "apply now" in the admin
# quietly becomes "apply whenever the box next checks in" -- while the monitor
# reports the router offline the whole time it is working perfectly.
#
# This end runs one interface. Each router dials out to it and gets a stable
# address on our side that we can always reach. The app allocates those addresses
# and generates each router's config; it deliberately does NOT touch this file,
# because reloading a shared interface every tenant depends on is not something a
# web request should do.
#
# Run it once. Re-running is safe: it only creates what is missing and prints the
# public key either way.

set -euo pipefail

IFACE="${ZIDI_VPN_IFACE:-zidi-vpn}"
SUBNET="${ZIDI_VPN_SUBNET:-10.77.0.0/24}"
ADDRESS="${ZIDI_VPN_ADDRESS:-10.77.0.1/24}"
PORT="${ZIDI_VPN_PORT:-13231}"
CONF="/etc/wireguard/${IFACE}.conf"

if [ "$(id -u)" -ne 0 ]; then
  echo "Run this with sudo -- it writes /etc/wireguard and brings an interface up." >&2
  exit 1
fi

if ! command -v wg >/dev/null 2>&1; then
  echo "==> Installing wireguard"
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update -qq && apt-get install -y wireguard >/dev/null
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y wireguard-tools >/dev/null
  else
    echo "No apt-get or dnf here. Install wireguard-tools by hand and re-run." >&2
    exit 1
  fi
fi

mkdir -p /etc/wireguard
chmod 700 /etc/wireguard

if [ -f "$CONF" ]; then
  echo "==> $CONF already exists, leaving it alone"
else
  echo "==> Generating the server key"
  # umask first: wg genkey writes to stdout but the redirect creates the file,
  # and a private key readable by everybody is the one mistake here that matters.
  umask 077
  PRIVATE="$(wg genkey)"
  cat > "$CONF" <<EOF
# Zidi management tunnel. Routers dial in; each gets one address.
#
# Peer blocks are added per router. The admin (Network -> Remote access) prints
# the exact block for each one -- AllowedIPs is a single /32 on purpose, so a
# router cannot send traffic claiming to be another router on the tunnel.
[Interface]
Address = ${ADDRESS}
ListenPort = ${PORT}
PrivateKey = ${PRIVATE}

# --- peers below this line ---
EOF
  chmod 600 "$CONF"
  echo "==> Wrote $CONF"
fi

echo "==> Enabling the interface"
systemctl enable "wg-quick@${IFACE}" >/dev/null 2>&1 || true
if systemctl is-active --quiet "wg-quick@${IFACE}"; then
  systemctl restart "wg-quick@${IFACE}"
else
  systemctl start "wg-quick@${IFACE}"
fi

# Routers dial UDP on this port from anywhere, so it has to be open. Nothing
# else about the tunnel is exposed.
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  ufw allow "${PORT}/udp" >/dev/null || true
  echo "==> Opened ${PORT}/udp in ufw"
else
  echo "==> Open ${PORT}/udp in your firewall or security group"
fi

PUBLIC="$(wg show "$IFACE" public-key)"

cat <<EOF

Done.

Paste these into the admin under Network -> Remote access:

  Server public key   ${PUBLIC}
  Endpoint            $(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || echo "<this server's public address>"):${PORT}
  Tunnel subnet       ${SUBNET}
  Our address         ${ADDRESS%%/*}

Then, for each router, press "Set up tunnel". That configures the router and
prints a peer block; add each block to the end of ${CONF} and run:

  wg syncconf ${IFACE} <(wg-quick strip ${IFACE})

syncconf rather than restart, so adding one router does not drop the tunnels of
all the others.
EOF
