-- Addresses are tracked in a spreadsheet, or in somebody's head. Splynx, Sonar
-- and UISP all have this and it is the last of the table-stakes gaps: the
-- moment an operator hands out static IPs, "which addresses are free" stops
-- being answerable and two customers get the same one.
--
-- Held as subnets plus the addresses actually allocated. Free addresses are not
-- rows: a /16 is sixty-five thousand of them, and a table that pre-creates
-- every one is unusable at exactly the scale where this starts to matter.

CREATE TABLE ip_subnets (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(120) NOT NULL,
    -- Stored as typed, e.g. 10.20.0.0/24. Kept as text rather than Postgres
    -- CIDR so the same parsing runs in the application for every database.
    cidr          VARCHAR(64)  NOT NULL UNIQUE,
    purpose       VARCHAR(20)  NOT NULL DEFAULT 'STATIC',
    -- The router's own address inside the subnet. Reserved automatically,
    -- because handing a customer the gateway address takes the site down.
    gateway       VARCHAR(64),
    vlan_id       INTEGER,
    router_id     BIGINT,
    branch_id     BIGINT,
    description   VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ip_subnets_purpose_check CHECK (purpose IN
        ('PPPOE', 'HOTSPOT', 'STATIC', 'MANAGEMENT', 'INFRASTRUCTURE', 'OTHER'))
);

-- One row per address actually handed out or held back. Absence means free.
CREATE TABLE ip_assignments (
    id            BIGSERIAL PRIMARY KEY,
    subnet_id     BIGINT       NOT NULL REFERENCES ip_subnets (id) ON DELETE CASCADE,
    address       VARCHAR(64)  NOT NULL,
    -- What it is for. RESERVED covers the gateway, the DHCP range and anything
    -- an operator wants kept out of the pool without inventing a customer.
    kind          VARCHAR(16)  NOT NULL DEFAULT 'ASSIGNED',
    subscriber_id BIGINT,
    device_id     BIGINT,
    mac_address   VARCHAR(64),
    hostname      VARCHAR(120),
    notes         VARCHAR(500),
    assigned_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    assigned_by   VARCHAR(120),

    -- The constraint that stops two customers being given the same address.
    -- Worth having in the database and not only in the service: an allocation
    -- race between two staff members is exactly how duplicates happen.
    CONSTRAINT ip_assignments_unique UNIQUE (subnet_id, address),
    CONSTRAINT ip_assignments_kind_check CHECK (kind IN ('ASSIGNED', 'RESERVED', 'GATEWAY'))
);

CREATE INDEX idx_ip_assignments_subnet ON ip_assignments (subnet_id);
CREATE INDEX idx_ip_assignments_subscriber ON ip_assignments (subscriber_id);

-- A subscriber's static address, so RADIUS can hand it out as
-- Framed-IP-Address at login instead of the pool picking one at random. Null
-- means "give them whatever is free", which is what most customers want.
ALTER TABLE subscribers ADD COLUMN static_ip VARCHAR(64);
