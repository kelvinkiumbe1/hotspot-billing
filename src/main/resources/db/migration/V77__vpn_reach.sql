-- Reaching a router that is behind carrier NAT.
--
-- This is the quiet reason half the network features only half work. A site
-- router on a mobile or a domestic line has no reachable address: the operator
-- can dial out from it, but nothing can dial in. So the MikroTik API cannot be
-- opened, a TR-069 connection request cannot be delivered, and every "apply now"
-- in this admin silently degrades to "apply whenever the box next checks in".
-- The router monitor shows it offline even while it is working perfectly.
--
-- The fix is a tunnel the ROUTER opens outward to us, giving it a stable address
-- on our side that we can always dial. WireGuard, because RouterOS 7 has it
-- built in, it survives a changing public IP without renegotiating, and it costs
-- almost nothing on a small board.
--
-- WHAT THIS TABLE DOES NOT DO: it does not run a VPN server. That is a host
-- concern -- one WireGuard interface on the machine this app runs on -- and
-- deploy/vpn-setup.sh writes it. What lives here is the part the application
-- genuinely owns: which router gets which tunnel address, what its public key
-- is, whether the tunnel has been seen working, and which address to try first
-- when opening a connection.
--
-- KEYS ARE NEVER GENERATED HERE. RouterOS generates its own private key when the
-- interface is created and we read back only the public half; the server's key is
-- made once with `wg genkey` at deploy time. So this table holds no private key
-- at all, and a database dump cannot impersonate anything on the tunnel.

CREATE TABLE vpn_settings (
    id                BIGINT PRIMARY KEY,
    enabled           BOOLEAN NOT NULL DEFAULT FALSE,

    -- The server half, from the host's own WireGuard interface. Public key only.
    server_public_key VARCHAR(64),
    -- What the router dials: a hostname or IP and a port. Must be reachable from
    -- the outside, which is the one thing this end has to be.
    endpoint          VARCHAR(255),

    -- The tunnel subnet, and our address inside it. Router addresses are handed
    -- out from this range.
    subnet            VARCHAR(32) NOT NULL DEFAULT '10.77.0.0/24',
    server_address    VARCHAR(64) NOT NULL DEFAULT '10.77.0.1',

    -- Seconds between keepalives. Needed in both directions or the NAT mapping
    -- the whole scheme depends on quietly expires and the tunnel goes one-way --
    -- which looks exactly like a working tunnel until you try to dial in.
    keepalive_seconds INTEGER NOT NULL DEFAULT 25,

    -- The interface name created on the ROUTER. Fixed per install so a second
    -- run finds the interface it made last time instead of adding another.
    interface_name    VARCHAR(64) NOT NULL DEFAULT 'zidi-vpn',

    updated_at        TIMESTAMP WITH TIME ZONE,
    updated_by        VARCHAR(120)
);

INSERT INTO vpn_settings (id, enabled) VALUES (1, FALSE);

-- Per router.
--
-- vpn_address is allocated by us and is the address we dial. vpn_public_key is
-- read back off the router after the interface exists, and is what the operator
-- pastes into the server's peer list -- until that is done the tunnel cannot come
-- up, so it is worth being able to see which routers are waiting on it.
ALTER TABLE routers ADD COLUMN vpn_address        VARCHAR(64);
ALTER TABLE routers ADD COLUMN vpn_public_key     VARCHAR(64);
ALTER TABLE routers ADD COLUMN vpn_configured_at  TIMESTAMP WITH TIME ZONE;
-- Last time a connection over the tunnel actually worked. Not a handshake time
-- read from WireGuard -- that would need the tunnel to be up to ask -- but our
-- own observation, which is the thing that matters and the only thing available
-- when the tunnel is down.
ALTER TABLE routers ADD COLUMN vpn_last_ok_at     TIMESTAMP WITH TIME ZONE;
ALTER TABLE routers ADD COLUMN vpn_last_error     VARCHAR(500);

CREATE UNIQUE INDEX uq_routers_vpn_address ON routers (vpn_address)
    WHERE vpn_address IS NOT NULL;
