-- The system already chases customers who have left: dunning when a payment
-- fails, win-back once they are gone. Both start after the loss. Nothing looks
-- at the customer who is still paying and has quietly stopped using their
-- connection, or whose renewal has slipped later every month for four months.
-- Those are the ones an operator can still keep, and they are invisible until
-- the day they cancel.

CREATE TABLE retention_scores (
    id                BIGSERIAL PRIMARY KEY,
    subscriber_id     BIGINT      NOT NULL UNIQUE,

    -- 0 = no sign of leaving, 100 = about to. Deliberately a plain number
    -- from named signals rather than a model: an operator who is told "at
    -- risk" can do nothing, while one told "hasn't been online in 19 days and
    -- paid 6 days late last month" knows what to say when they ring.
    score             INTEGER     NOT NULL DEFAULT 0,
    band              VARCHAR(12) NOT NULL DEFAULT 'STEADY',

    -- The signals that fired, worst first, in plain words.
    reasons           VARCHAR(1000),
    -- What to do about it, or null when there is nothing to do.
    suggested_action  VARCHAR(255),

    -- Kept so a trend can be read: a score that climbed 30 points in a week is
    -- a different conversation from one that has been high and flat all year.
    previous_score    INTEGER,
    scored_at         TIMESTAMPTZ NOT NULL,

    -- Set when somebody has looked at it, so the same name does not sit at the
    -- top of the list accusing everyone of ignoring it.
    acknowledged_at   TIMESTAMPTZ,
    acknowledged_by   VARCHAR(120),

    CONSTRAINT retention_scores_band_check CHECK (band IN ('STEADY', 'WATCH', 'AT_RISK', 'CRITICAL'))
);

CREATE INDEX idx_retention_scores_score ON retention_scores (score DESC);

-- What a customer is actually getting, as against what they pay for.
--
-- Every plan sells a speed and nothing has ever checked whether it arrives.
-- That matters twice: a customer paying for 10M and receiving 2M will leave
-- and will not say why, and an operator accused of it has had no way to answer
-- either. Both need the same measurement.
CREATE TABLE delivered_speed (
    id                BIGSERIAL PRIMARY KEY,
    subscriber_id     BIGINT      NOT NULL,
    observed_on       DATE        NOT NULL,

    -- The busiest minute we saw, in bits per second. A peak rather than an
    -- average, because an average over a day is mostly the customer being
    -- asleep and says nothing about what they get when they use it.
    peak_down_bps     BIGINT      NOT NULL DEFAULT 0,
    peak_up_bps       BIGINT      NOT NULL DEFAULT 0,
    -- What they bought, at the time — plans change, and last month's shortfall
    -- must not be recomputed against this month's price list.
    plan_down_bps     BIGINT,
    plan_up_bps       BIGINT,
    samples           INTEGER     NOT NULL DEFAULT 0,

    CONSTRAINT delivered_speed_unique UNIQUE (subscriber_id, observed_on)
);

CREATE INDEX idx_delivered_speed_day ON delivered_speed (observed_on);
