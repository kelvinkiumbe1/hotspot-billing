-- How much a fibre or PPPoE customer has actually used, and keeping it.
--
-- Until now subscribers.data_used_mb was the whole story: one running counter,
-- zeroed on the 1st of every month by RouterMonitorJob.resetMonthlyUsage(). That
-- reset does not archive anything, so every month the previous month's usage was
-- destroyed. There was no way to answer "how much did this customer use in June",
-- no way to show a customer their own history, and no way to enforce a data cap
-- on anything other than a hotspot voucher.
--
-- The bytes were never the problem. RADIUS accounting already counts them per
-- session and already adds them to that counter; they just had nowhere durable to
-- land. This gives them one.
--
-- A row per customer per day, not per hour. traffic_usage is hourly because a
-- hotspot pass lives for hours and an hour is the unit that means something
-- there; a fibre line lives for years, and hourly rows for a two-thousand-line
-- network is seventeen million rows a year to answer a question nobody asks by
-- the hour. Daily is what the invoice, the usage graph and the fair-use cap all
-- work in.
--
-- Bytes rather than megabytes, unlike the counter it supersedes. data_used_mb
-- divides by 1048576 on every increment and throws the remainder away, so a
-- customer generating a steady trickle of small updates could be billed
-- meaningfully less than they used. BIGINT holds nine exabytes; there is no
-- reason to round anything away here.

CREATE TABLE subscriber_usage_daily (
    id            BIGSERIAL PRIMARY KEY,
    subscriber_id BIGINT NOT NULL,
    day           DATE   NOT NULL,
    bytes_up      BIGINT NOT NULL DEFAULT 0,
    bytes_down    BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_subscriber_usage_daily UNIQUE (subscriber_id, day)
);

-- The two shapes anything here reads in: one customer over a range (their usage
-- page, their cap), and every customer on one day (the top-talkers report).
CREATE INDEX idx_sub_usage_subscriber ON subscriber_usage_daily (subscriber_id, day);
CREATE INDEX idx_sub_usage_day        ON subscriber_usage_daily (day);

-- Fair use for subscribers.
--
-- Deliberately on the subscriber and not on a plan. Vouchers get their cap from
-- plans.fup_limit_mb because a pass IS a plan; a subscriber has no plan at all --
-- their package is the bandwidth string and monthly_fee carried on the row
-- itself. Adding a plan link only to hang a cap on it would be a schema change
-- reaching a long way for one number.
--
-- NULL cap means uncapped, which is what every existing subscriber gets and what
-- most fibre customers should stay on.
ALTER TABLE subscribers ADD COLUMN data_cap_mb     INTEGER;
ALTER TABLE subscribers ADD COLUMN fup_action      VARCHAR(16);
ALTER TABLE subscribers ADD COLUMN fup_rate        VARCHAR(40);
ALTER TABLE subscribers ADD COLUMN fup_applied_at  TIMESTAMP WITH TIME ZONE;
-- Which cap period the action was applied in, so clearing it is a comparison
-- rather than a job that has to run at midnight on the 1st and be trusted to.
ALTER TABLE subscribers ADD COLUMN fup_cycle       DATE;
