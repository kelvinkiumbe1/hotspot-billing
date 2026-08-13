-- Cumulative data used on a hotspot pass (bytes up+down, surviving reconnects)
-- and a once-only guard for the "you're almost out of data — top up" nudge.
ALTER TABLE vouchers ADD COLUMN used_bytes     BIGINT NOT NULL DEFAULT 0;
ALTER TABLE vouchers ADD COLUMN data_nudged_at TIMESTAMPTZ;
