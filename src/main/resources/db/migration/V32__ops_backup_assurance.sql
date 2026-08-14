-- Operational assurance. The backup script has existed and been documented for
-- a while; what has never existed is anything that notices when it stops
-- running, or checks that what it produced can actually be restored. A backup
-- nobody verifies is a rumour.
CREATE TABLE ops_settings (
    id                     BIGINT  PRIMARY KEY,
    -- Backups
    backup_watch_enabled   BOOLEAN NOT NULL DEFAULT true,
    backup_expected_hours  INTEGER NOT NULL DEFAULT 26,
    backup_min_bytes       BIGINT  NOT NULL DEFAULT 4096,
    -- Health (used from V33 on)
    health_watch_enabled   BOOLEAN NOT NULL DEFAULT true,
    callback_silence_hours INTEGER NOT NULL DEFAULT 6,
    quiet_from_hour        INTEGER NOT NULL DEFAULT 22,
    quiet_to_hour          INTEGER NOT NULL DEFAULT 6,
    heartbeat_url          VARCHAR(512)
);

CREATE TABLE backup_reports (
    id           BIGSERIAL   PRIMARY KEY,
    tenant       VARCHAR(64) NOT NULL,
    ok           BOOLEAN     NOT NULL,
    bytes        BIGINT      NOT NULL DEFAULT 0,
    -- Whether the dump was restored into a scratch database to prove it reads
    verified     BOOLEAN     NOT NULL DEFAULT false,
    -- Whether a copy reached somewhere other than this machine's disk
    offsite      BOOLEAN     NOT NULL DEFAULT false,
    duration_ms  BIGINT      NOT NULL DEFAULT 0,
    error        VARCHAR(500),
    reported_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_backup_reports_time ON backup_reports (reported_at DESC);
