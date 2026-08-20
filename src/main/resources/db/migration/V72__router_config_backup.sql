-- Keeping a copy of what is on each router.
--
-- There was none. A MikroTik that dies -- and they die, usually from a power
-- event -- takes its configuration with it: every PPPoE secret, every queue,
-- every firewall rule, every hotspot profile. What follows is somebody
-- rebuilding it from memory at two in the morning while the whole network is
-- down, and getting it approximately right.
--
-- A row per VERSION, not per night.
--
-- The obvious design is a nightly snapshot with a retention window, which on a
-- ten-router network is 3,650 rows a year of which perhaps forty differ from the
-- one before. Storing only what changed inverts that: the table becomes a
-- history of changes rather than a pile of duplicates, "when did this router
-- last change" stops being a scan, and a year of history costs less than a week
-- of the naive version. A config nobody has touched since March is one row that
-- says so.
--
-- last_seen_at is what makes that safe. Without it, an unchanged config is
-- indistinguishable from a router nobody has managed to reach since March, which
-- is the exact failure this is meant to protect against.

CREATE TABLE router_backups (
    id            BIGSERIAL PRIMARY KEY,
    router_id     BIGINT NOT NULL,
    -- When this version of the config first appeared, and when we last confirmed
    -- the router is still running it.
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    -- SHA-256 of the content, which is how "has anything changed" is answered
    -- without pulling a few hundred KB of text out of the database every night.
    checksum      VARCHAR(64) NOT NULL,
    -- How it was captured. Recorded because there is more than one way and they
    -- do not produce the same thing -- see RouterConfigBackupService.
    method        VARCHAR(24) NOT NULL,
    line_count    INTEGER NOT NULL,
    byte_count    INTEGER NOT NULL,
    content       TEXT NOT NULL
);

CREATE INDEX idx_router_backups_router ON router_backups (router_id, first_seen_at DESC);

-- The outcome of the last attempt, successful or not.
--
-- Kept on the router rather than as a row per attempt, because the question is
-- always "is this router being backed up" and never "what happened on the night
-- of the 4th". The lesson is BackupWatchService's: a job that says nothing must
-- never be read as a job that succeeded, so a failure has to be visible
-- somewhere a person looks rather than only in a log.
ALTER TABLE routers ADD COLUMN config_backup_at    TIMESTAMP WITH TIME ZONE;
ALTER TABLE routers ADD COLUMN config_backup_error VARCHAR(500);
