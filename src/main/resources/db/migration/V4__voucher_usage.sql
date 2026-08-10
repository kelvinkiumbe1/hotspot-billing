-- Track how much of each voucher has actually been used, so a router reboot
-- (or a wiped/replaced router) can restore the *remaining* time rather than
-- either locking the customer out or handing them a fresh full pass.
--
-- used_seconds is the app's authoritative running total. router_uptime_seconds
-- is the last counter we read from the router, kept so we can add the delta
-- since the previous poll and cope with the counter resetting on reboot.

ALTER TABLE vouchers
    ADD COLUMN used_seconds          BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN router_uptime_seconds BIGINT NOT NULL DEFAULT 0;
