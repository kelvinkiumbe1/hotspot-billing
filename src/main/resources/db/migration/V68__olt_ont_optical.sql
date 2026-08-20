-- Seeing the light on a fibre, which is the question fibre ISPs ask most.
--
-- The plant was already modelled -- fiber_nodes knows POPs, OLTs, closures,
-- splitters and drops, with capacity and parentage. What was missing was anything
-- that talks to the fibre: nothing read an ONU's optical power, so "why is this
-- customer slow" had no answer here and a technician went out to look.
--
-- Read-only, over SNMP, reusing the client and the change-detecting poll that
-- already exist for switches. Nothing here can change a customer's service.

-- An OLT is a network_device like any other, so it needs to be an allowed kind.
ALTER TABLE network_devices DROP CONSTRAINT IF EXISTS network_devices_kind_check;
ALTER TABLE network_devices ADD CONSTRAINT network_devices_kind_check
    CHECK (kind IN ('SWITCH', 'ACCESS_POINT', 'ONT', 'UPS', 'SERVER', 'ROUTER', 'OLT', 'OTHER'));

-- Where to find the ONUs. All null on anything that is not an OLT, and null on an
-- OLT means "use the vendor preset".
--
-- These columns exist because the presets cannot be verified. GPON ONU tables
-- live in enterprise MIBs with nothing in common between vendors, and unlike
-- every payment API in this codebase there is no sandbox anywhere to check an OID
-- against -- a wrong one returns nothing, which reads as "this OLT has no ONUs"
-- rather than as an error. An operator with snmpwalk and their own OLT can find
-- the right column and type it in, instead of waiting for a release and another
-- guess.
ALTER TABLE network_devices ADD COLUMN olt_vendor        VARCHAR(20);
ALTER TABLE network_devices ADD COLUMN onu_serial_oid    VARCHAR(120);
ALTER TABLE network_devices ADD COLUMN onu_rx_power_oid  VARCHAR(120);
ALTER TABLE network_devices ADD COLUMN onu_tx_power_oid  VARCHAR(120);
ALTER TABLE network_devices ADD COLUMN onu_status_oid    VARCHAR(120);

-- The scale matters as much as the OID. Huawei sends hundredths of a dBm as a
-- signed integer; others send thousandths, tenths, or raw microwatts needing a
-- logarithm. Read with the wrong one, a healthy -24.6 dBm becomes -2456 or -0.2,
-- and a technician is sent to a working fibre while the broken one waits.
ALTER TABLE network_devices ADD COLUMN onu_power_unit    VARCHAR(20);
ALTER TABLE network_devices ADD COLUMN onu_power_scale   DOUBLE PRECISION;

-- One row per ONU, updated in place rather than appended.
--
-- A full OLT is a couple of thousand ONUs; polled every five minutes a history
-- table would be half a million rows a day for a question nobody asks. Both
-- questions anybody does ask -- what is this customer's light, and which drops
-- are failing -- are answered by the latest reading.
CREATE TABLE ont_readings (
    id               BIGSERIAL PRIMARY KEY,
    olt_device_id    BIGINT       NOT NULL,
    -- The serial, not the SNMP table index. The index moves when ONUs are added
    -- or removed, so keying on it would quietly start attributing one customer's
    -- readings to another after the first unit anybody swapped.
    serial           VARCHAR(64)  NOT NULL,
    description      VARCHAR(160),
    table_index      INTEGER,
    status           VARCHAR(40),
    rx_dbm           DOUBLE PRECISION,
    tx_dbm           DOUBLE PRECISION,
    -- Exactly one value from the past, and it earns its column: it separates
    -- "this link has always been poor", which is a bad install, from "this link
    -- was fine yesterday", which is a fibre cut this morning. Different vans.
    previous_rx_dbm  DOUBLE PRECISION,
    health           VARCHAR(20),
    -- Set by a person, never guessed. An OLT knows serials and billing knows
    -- customers, and nothing connects the two on its own.
    subscriber_id    BIGINT,
    last_seen_at     TIMESTAMPTZ,
    -- So the same failing drop is not reported every five minutes.
    last_alerted_at  TIMESTAMPTZ,
    CONSTRAINT ont_readings_unique UNIQUE (olt_device_id, serial)
);

CREATE INDEX ont_readings_olt_idx ON ont_readings (olt_device_id);
-- The list an operator opens is "worst first", so health is what it sorts on.
CREATE INDEX ont_readings_health_idx ON ont_readings (health);
CREATE INDEX ont_readings_subscriber_idx ON ont_readings (subscriber_id);
