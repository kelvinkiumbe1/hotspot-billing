-- Everything this system can see, it sees through the MikroTik API. That makes
-- the router the only monitored object on a network that is mostly not routers:
-- the switch in the cabinet, the sector antenna on the mast, the ONT in the
-- customer's house, the UPS keeping all of it alive. When one of those fails,
-- the router stays up and reports nothing wrong, and the operator finds out
-- from a customer.
--
-- SNMP is what those devices already speak. Nothing has to be installed on
-- them; they have been answering these questions all along and nobody has been
-- asking.

CREATE TABLE network_devices (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(120) NOT NULL UNIQUE,
    kind              VARCHAR(20)  NOT NULL DEFAULT 'OTHER',
    host              VARCHAR(120) NOT NULL,
    port              INTEGER      NOT NULL DEFAULT 161,
    location          VARCHAR(160),
    branch_id         BIGINT,
    enabled           BOOLEAN      NOT NULL DEFAULT true,

    -- v2c sends the community string in clear text on every poll. It is still
    -- what most switches ship with, so it is supported — but v3 is offered
    -- beside it rather than as an afterthought, because a management VLAN is
    -- not always as isolated as the person who built it believes.
    snmp_version      VARCHAR(8)   NOT NULL DEFAULT 'V2C',
    community         VARCHAR(120),
    security_name     VARCHAR(120),
    auth_protocol     VARCHAR(16),
    auth_passphrase   VARCHAR(255),
    priv_protocol     VARCHAR(16),
    priv_passphrase   VARCHAR(255),

    -- Live state, rewritten every poll.
    online            BOOLEAN      NOT NULL DEFAULT false,
    last_seen_at      TIMESTAMPTZ,
    last_checked_at   TIMESTAMPTZ,
    last_error        VARCHAR(500),
    sys_name          VARCHAR(255),
    sys_descr         VARCHAR(500),
    sys_location      VARCHAR(255),
    sys_contact       VARCHAR(255),
    -- Seconds since the device booted. Read rather than inferred, because a
    -- device that reboots nightly at 3am looks perfectly healthy to a poller
    -- that only ever asks whether it answers.
    uptime_seconds    BIGINT,
    last_reboot_at    TIMESTAMPTZ,
    notes             VARCHAR(1000),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT network_devices_kind_check CHECK (kind IN
        ('SWITCH', 'ACCESS_POINT', 'ONT', 'UPS', 'SERVER', 'ROUTER', 'OTHER')),
    CONSTRAINT network_devices_version_check CHECK (snmp_version IN ('V1', 'V2C', 'V3'))
);

CREATE INDEX idx_network_devices_enabled ON network_devices (enabled);

-- One row per port. This is where the value actually is: a switch being "up"
-- says almost nothing, while an uplink that has quietly renegotiated to 100M,
-- or a port whose error counter has been climbing for a week, is the fault
-- that is about to become an outage.
CREATE TABLE device_interfaces (
    id                BIGSERIAL PRIMARY KEY,
    device_id         BIGINT       NOT NULL REFERENCES network_devices (id) ON DELETE CASCADE,
    if_index          INTEGER      NOT NULL,
    if_name           VARCHAR(120),
    -- What a human typed on the switch to say what this port is for
    -- ("uplink to core"). Worth more than any number here.
    if_alias          VARCHAR(255),
    if_descr          VARCHAR(255),

    admin_up          BOOLEAN      NOT NULL DEFAULT false,
    oper_up           BOOLEAN      NOT NULL DEFAULT false,
    speed_bps         BIGINT,
    last_change_at    TIMESTAMPTZ,

    -- Counters are cumulative and wrap; only the delta between polls means
    -- anything, so the previous reading is kept beside the rate derived from it.
    last_in_octets    BIGINT,
    last_out_octets   BIGINT,
    last_in_errors    BIGINT,
    last_out_errors   BIGINT,
    counters_at       TIMESTAMPTZ,
    in_bps            BIGINT,
    out_bps           BIGINT,
    -- Errors since the last poll, not since the device booted. A lifetime
    -- total of 40,000 on a switch that has been up for three years is noise;
    -- forty in the last five minutes is a cable about to fail.
    in_errors_delta   BIGINT       NOT NULL DEFAULT 0,
    out_errors_delta  BIGINT       NOT NULL DEFAULT 0,

    -- Ports that are meant to be down should not page anyone at 2am. Off by
    -- default: alerting on every unused access port is how an operator learns
    -- to ignore the alerts.
    monitored         BOOLEAN      NOT NULL DEFAULT false,
    updated_at        TIMESTAMPTZ,

    CONSTRAINT device_interfaces_unique UNIQUE (device_id, if_index)
);

CREATE INDEX idx_device_interfaces_device ON device_interfaces (device_id);
