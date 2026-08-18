-- Every user this system creates is written onto a MikroTik through the
-- RouterOS API. That is the product's real ceiling: an operator running Cisco,
-- Ubiquiti, Cambium, Ruckus or TP-Link Omada cannot use any of it, and an
-- operator running four MikroTiks needs every voucher copied onto all four and
-- kept in step by hand.
--
-- RADIUS inverts it. Instead of pushing users out to each router, the routers
-- ask us on every login. One answer, one place, and any vendor that speaks
-- RADIUS — which is all of them — works without a line of vendor code.

-- Which routers are allowed to ask. A RADIUS server that answers anyone is an
-- oracle for guessing usernames, so an unknown source gets silence rather
-- than a rejection: a reject confirms the server is there.
CREATE TABLE radius_clients (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(120) NOT NULL,
    -- A single address or a CIDR block, for operators whose NAS pool moves.
    address        VARCHAR(64)  NOT NULL UNIQUE,
    shared_secret  VARCHAR(255) NOT NULL,
    -- Only affects which vendor attributes are sent back. Getting it wrong
    -- costs the speed limit, not the login.
    vendor         VARCHAR(16)  NOT NULL DEFAULT 'GENERIC',
    router_id      BIGINT,
    enabled        BOOLEAN      NOT NULL DEFAULT true,
    -- The CoA/Disconnect port, for kicking a session off mid-flight. 3799 is
    -- the RFC number; MikroTik and most others use it, some use 1700.
    coa_port       INTEGER      NOT NULL DEFAULT 3799,
    last_request_at TIMESTAMPTZ,
    accepts        BIGINT       NOT NULL DEFAULT 0,
    rejects        BIGINT       NOT NULL DEFAULT 0,
    notes          VARCHAR(500),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT radius_clients_vendor_check CHECK (vendor IN
        ('MIKROTIK', 'CISCO', 'UBIQUITI', 'CAMBIUM', 'RUCKUS', 'OMADA', 'GENERIC'))
);

-- What is connected right now, and what it used.
--
-- This is the same information the router poller collects today, except the
-- router volunteers it instead of being asked every two minutes. That closes
-- the gap where somebody connects and disconnects between two polls and the
-- system never knows they were there at all.
CREATE TABLE radius_sessions (
    id              BIGSERIAL PRIMARY KEY,
    -- The NAS's own id for this session. Unique per NAS, not globally, so the
    -- pair is what identifies a session.
    acct_session_id VARCHAR(120) NOT NULL,
    nas_address     VARCHAR(64)  NOT NULL,
    username        VARCHAR(120) NOT NULL,
    kind            VARCHAR(10)  NOT NULL DEFAULT 'HOTSPOT',

    voucher_id      BIGINT,
    subscriber_id   BIGINT,

    framed_ip       VARCHAR(64),
    calling_station VARCHAR(64),
    called_station  VARCHAR(64),
    nas_port_id     VARCHAR(120),

    started_at      TIMESTAMPTZ  NOT NULL,
    last_update_at  TIMESTAMPTZ  NOT NULL,
    stopped_at      TIMESTAMPTZ,
    terminate_cause VARCHAR(64),

    -- Counters as the NAS last reported them, cumulative for the session.
    in_octets       BIGINT       NOT NULL DEFAULT 0,
    out_octets      BIGINT       NOT NULL DEFAULT 0,
    session_seconds BIGINT       NOT NULL DEFAULT 0,
    -- What we have already folded into the voucher, so an interim update that
    -- arrives twice cannot bill the customer's time twice.
    applied_octets  BIGINT       NOT NULL DEFAULT 0,
    applied_seconds BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT radius_sessions_unique UNIQUE (nas_address, acct_session_id),
    CONSTRAINT radius_sessions_kind_check CHECK (kind IN ('HOTSPOT', 'PPPOE'))
);

CREATE INDEX idx_radius_sessions_open ON radius_sessions (stopped_at) WHERE stopped_at IS NULL;
CREATE INDEX idx_radius_sessions_username ON radius_sessions (username);
CREATE INDEX idx_radius_sessions_voucher ON radius_sessions (voucher_id);

-- One row, like the other settings tables.
CREATE TABLE radius_settings (
    id                BIGINT PRIMARY KEY DEFAULT 1,
    enabled           BOOLEAN NOT NULL DEFAULT false,
    auth_port         INTEGER NOT NULL DEFAULT 1812,
    acct_port         INTEGER NOT NULL DEFAULT 1813,
    -- How often the NAS should send an update on a live session. Five minutes
    -- bounds how much unbilled time can be lost if a router dies without
    -- sending a Stop, which is the ordinary way sessions end in the field.
    interim_seconds   INTEGER NOT NULL DEFAULT 300,
    -- Cut a customer off the moment their pass runs out, rather than waiting
    -- for the NAS to honour Session-Timeout. Needs the NAS to accept CoA.
    disconnect_enabled BOOLEAN NOT NULL DEFAULT true,
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(120),

    CONSTRAINT radius_settings_singleton CHECK (id = 1)
);

INSERT INTO radius_settings (id) VALUES (1);
