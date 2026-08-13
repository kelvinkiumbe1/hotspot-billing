-- Once-only guards: when a fair-use action was applied to a pass, and when a
-- pass was flagged for being used on several devices at once (sharing).
ALTER TABLE vouchers ADD COLUMN fup_applied_at     TIMESTAMPTZ;
ALTER TABLE vouchers ADD COLUMN sharing_flagged_at TIMESTAMPTZ;
