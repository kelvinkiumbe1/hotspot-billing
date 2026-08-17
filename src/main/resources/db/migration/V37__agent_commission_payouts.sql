-- Agent commission has always been *calculated* — derived from the vouchers in
-- an agent's batches that customers actually used, so it cannot drift. Paying
-- it was entirely manual: work out who is owed what, send each one M-Pesa by
-- hand, then remember to come back and record it. The recording step is the
-- one that gets skipped, and an agent who is paid but not recorded gets paid
-- again next month.
--
-- So the run is scheduled, the send goes through Daraja B2C, and — the part
-- that matters — an agent's paid total only moves when Safaricom confirms the
-- money landed. A payout that failed must never look settled.
CREATE TABLE agent_payout_settings (
    id              BIGINT        PRIMARY KEY,
    enabled         BOOLEAN       NOT NULL DEFAULT false,
    -- Off means payouts are prepared and wait for a human to release them
    auto_send       BOOLEAN       NOT NULL DEFAULT false,
    -- Below this, leave it to roll over; a KES 40 transfer costs more in fees
    minimum_amount  NUMERIC(12,2) NOT NULL DEFAULT 500,
    -- A ceiling on one run, so a bug cannot empty the float in one pass
    max_per_run     NUMERIC(12,2) NOT NULL DEFAULT 20000,
    frequency       VARCHAR(16)   NOT NULL DEFAULT 'WEEKLY',
    day_of_week     INTEGER       NOT NULL DEFAULT 1,
    day_of_month    INTEGER       NOT NULL DEFAULT 1,
    run_hour        INTEGER       NOT NULL DEFAULT 9,
    -- Blank means pay from the same shortcode that collects
    b2c_short_code  VARCHAR(20),
    last_run_on     DATE
);

INSERT INTO agent_payout_settings (id) VALUES (1);

CREATE TABLE commission_payouts (
    id              BIGSERIAL     PRIMARY KEY,
    agent_id        BIGINT        NOT NULL,
    amount          NUMERIC(12,2) NOT NULL,
    -- PENDING (waiting for release) -> SENT (Daraja accepted) -> PAID | FAILED.
    -- MANUAL is money the operator moved themselves and is recording here.
    status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    -- Correlates the async B2C result that decides PAID or FAILED
    conversation_id VARCHAR(120),
    receipt         VARCHAR(64),
    error           VARCHAR(500),
    created_by      VARCHAR(120),
    created_at      TIMESTAMPTZ   NOT NULL,
    sent_at         TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ
);
CREATE INDEX idx_commission_payouts_status ON commission_payouts (status, created_at DESC);
CREATE INDEX idx_commission_payouts_agent  ON commission_payouts (agent_id, created_at DESC);
