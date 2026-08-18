-- Every Western ISP platform auto-charges a stored card each month. This one
-- sends an STK push and hopes the customer enters their PIN, then chases them
-- when they do not: dunning, win-back, expiry nudges. All of that machinery
-- exists to recover a renewal that a standing order would simply have
-- collected.
--
-- M-Pesa Ratiba is Safaricom's standing order. The customer approves once, on
-- their handset, and the monthly debit happens without anyone being asked
-- again. A subscriber with a live mandate is not dunned, not auto-prompted and
-- not nudged, because there is nothing to chase.
CREATE TABLE payment_mandates (
    id              BIGSERIAL PRIMARY KEY,
    subscriber_id   BIGINT      NOT NULL UNIQUE,
    provider        VARCHAR(24) NOT NULL,

    -- What the provider calls it. For Ratiba this is the standing order name
    -- we chose, which is the only handle Safaricom gives back.
    external_ref    VARCHAR(120),
    -- PENDING until the customer approves on their handset. That approval is
    -- not instant and not guaranteed, so a pending mandate must never be
    -- treated as a reason to stop chasing a renewal.
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',

    amount          NUMERIC(12,2) NOT NULL,
    frequency       VARCHAR(16) NOT NULL DEFAULT 'MONTHLY',
    starts_on       DATE        NOT NULL,
    ends_on         DATE,

    -- When the provider last actually took money under this mandate. A mandate
    -- that has been ACTIVE for three months and never collected is broken, and
    -- looks identical to a working one without this.
    last_collected_at TIMESTAMPTZ,
    collections     INTEGER     NOT NULL DEFAULT 0,
    last_error      VARCHAR(500),

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      VARCHAR(120),
    cancelled_at    TIMESTAMPTZ,

    CONSTRAINT payment_mandates_status_check CHECK (status IN
        ('PENDING', 'ACTIVE', 'CANCELLED', 'FAILED')),
    CONSTRAINT payment_mandates_frequency_check CHECK (frequency IN
        ('WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY'))
);

CREATE INDEX idx_payment_mandates_status ON payment_mandates (status);
