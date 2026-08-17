-- Capacity is the one thing an operator cannot fix in an afternoon. Backhaul
-- is ordered weeks ahead, and the signal that it is needed — the busy hour
-- creeping up, week after week — is invisible in a dashboard that only shows
-- right now. By the time customers complain about the evenings, the fix is a
-- month away.
--
-- The traffic to see it coming has been recorded all along. This reads the
-- busy hour per site, watches its trend, and says how long there is left.
--
-- Deliberately advisory: it recommends, it does not reconfigure anything.
-- Buying backhaul is not a decision to automate.
ALTER TABLE routers ADD COLUMN capacity_mbps INTEGER;

CREATE TABLE capacity_settings (
    id                  BIGINT  PRIMARY KEY,
    enabled             BOOLEAN NOT NULL DEFAULT false,
    -- Weeks of history to read the trend from
    lookback_days       INTEGER NOT NULL DEFAULT 28,
    -- Busy-hour throughput above this share of capacity is "getting full"
    warn_percent        INTEGER NOT NULL DEFAULT 70,
    critical_percent    INTEGER NOT NULL DEFAULT 90,
    -- A site under this share is capacity bought and not sold
    underused_percent   INTEGER NOT NULL DEFAULT 20,
    -- Message the operator with the week's findings
    notify              BOOLEAN NOT NULL DEFAULT false,
    notify_day_of_week  INTEGER NOT NULL DEFAULT 1,
    notify_hour         INTEGER NOT NULL DEFAULT 8,
    last_notified_on    DATE
);

INSERT INTO capacity_settings (id) VALUES (1);
