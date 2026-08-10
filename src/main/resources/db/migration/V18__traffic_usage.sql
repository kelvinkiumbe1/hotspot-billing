-- Persist hotspot traffic so the analytics page can report data usage, not
-- just money. The router only exposes *live, cumulative* byte counters per
-- active session; the monitor job reads them each poll, diffs against the
-- per-voucher cursor below, and folds the delta into one row per
-- (hour, router, user). Every traffic report (per-router, heatmap, upload vs
-- download, usage-by-plan, top talkers, current-vs-previous) is an
-- aggregation of this one table.
--
-- History only accrues from first capture — there is nothing to backfill,
-- because these bytes were never recorded before.

CREATE TABLE traffic_usage (
    id          BIGSERIAL PRIMARY KEY,
    -- Hour bucket (UTC, truncated to the hour) the traffic was observed in.
    bucket_hour TIMESTAMP WITH TIME ZONE NOT NULL,
    router_id   BIGINT NOT NULL,
    -- Hotspot username = voucher code; the customer identity for top-talkers
    -- and distinct-user heatmap counts.
    user_key    VARCHAR(128) NOT NULL,
    -- Plan the traffic is attributed to (via the user's voucher), when known.
    plan_id     BIGINT,
    bytes_up    BIGINT NOT NULL DEFAULT 0,
    bytes_down  BIGINT NOT NULL DEFAULT 0,
    -- One accumulating row per user per hour on each router.
    CONSTRAINT uq_traffic_usage UNIQUE (bucket_hour, router_id, user_key)
);

CREATE INDEX idx_traffic_usage_bucket ON traffic_usage (bucket_hour);
CREATE INDEX idx_traffic_usage_plan   ON traffic_usage (plan_id);

-- Per-voucher byte cursors, mirroring the existing router_uptime_seconds
-- pattern: the last cumulative counters read from the router, so each poll
-- adds only the delta and a counter that reset (reboot) is handled.
ALTER TABLE vouchers
    ADD COLUMN last_bytes_in  BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_bytes_out BIGINT NOT NULL DEFAULT 0,
    -- The router this voucher was actually seen active on, so per-router
    -- revenue (payment -> voucher -> router) becomes derivable.
    ADD COLUMN router_id      BIGINT;
