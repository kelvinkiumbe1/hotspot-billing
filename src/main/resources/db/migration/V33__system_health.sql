-- The system watches routers closely and itself not at all. These two tables
-- fix that: every scheduled job stamps that it ran, and anything found wrong
-- becomes an alert that clears itself when the condition goes away.
CREATE TABLE job_heartbeats (
    job_name    VARCHAR(64) PRIMARY KEY,
    last_run_at TIMESTAMPTZ NOT NULL,
    last_note   VARCHAR(300)
);

CREATE TABLE health_alerts (
    id            BIGSERIAL    PRIMARY KEY,
    -- One row per condition, so a fault that persists ages rather than repeats
    check_key     VARCHAR(120) NOT NULL UNIQUE,
    severity      VARCHAR(10)  NOT NULL,
    title         VARCHAR(160) NOT NULL,
    detail        VARCHAR(500) NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    first_seen_at TIMESTAMPTZ  NOT NULL,
    last_seen_at  TIMESTAMPTZ  NOT NULL,
    resolved_at   TIMESTAMPTZ,
    notified_at   TIMESTAMPTZ
);
CREATE INDEX idx_health_alerts_status ON health_alerts (status, severity);
