-- Revenue assurance: a nightly cross-check of money in against service out.
-- Anything that doesn't reconcile becomes a finding the operator can work
-- through; findings that stop being detected close themselves.
CREATE TABLE revenue_audit_settings (
    id                BIGINT  PRIMARY KEY,
    enabled           BOOLEAN NOT NULL DEFAULT true,
    alert_operator    BOOLEAN NOT NULL DEFAULT true,
    unmatched_hours   INTEGER NOT NULL DEFAULT 24,
    lapsed_grace_days INTEGER NOT NULL DEFAULT 1,
    lookback_days     INTEGER NOT NULL DEFAULT 60,
    ignored_accounts  VARCHAR(2000),
    last_run_at       TIMESTAMPTZ
);

CREATE TABLE revenue_findings (
    id            BIGSERIAL    PRIMARY KEY,
    -- kind + subject, so the same problem seen on two nights is one row
    fingerprint   VARCHAR(220) NOT NULL UNIQUE,
    kind          VARCHAR(40)  NOT NULL,
    severity      VARCHAR(10)  NOT NULL,
    subject       VARCHAR(200) NOT NULL,
    detail        VARCHAR(500) NOT NULL,
    amount        NUMERIC(12, 2),
    status        VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    first_seen_at TIMESTAMPTZ  NOT NULL,
    last_seen_at  TIMESTAMPTZ  NOT NULL,
    resolved_at   TIMESTAMPTZ,
    resolved_by   VARCHAR(100),
    note          VARCHAR(300)
);
CREATE INDEX idx_revenue_findings_status ON revenue_findings (status, severity);
