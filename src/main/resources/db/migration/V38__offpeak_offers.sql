-- An ISP's cost is the link, and the link is paid for whether anyone is using
-- it or not. Between about ten at night and six in the morning most of it goes
-- to waste, and a customer who would happily pay eighty shillings for the
-- night is never asked.
--
-- This finds the genuinely idle hours from the traffic the system already
-- records, runs a discount across them, and tells the people most likely to
-- take it. The window can be worked out from the data or set by hand — the
-- operator knows their estate, and the data only knows last month.
CREATE TABLE offpeak_settings (
    id                        BIGINT      PRIMARY KEY,
    enabled                   BOOLEAN     NOT NULL DEFAULT false,
    -- Work the window out from traffic, rather than using the hours below
    auto_window               BOOLEAN     NOT NULL DEFAULT true,
    -- How much history to judge "quiet" from
    lookback_days             INTEGER     NOT NULL DEFAULT 14,
    window_start_hour         INTEGER     NOT NULL DEFAULT 22,
    window_end_hour           INTEGER     NOT NULL DEFAULT 6,
    discount_percent          INTEGER     NOT NULL DEFAULT 30,
    -- Message people when the window opens, and who
    notify                    BOOLEAN     NOT NULL DEFAULT false,
    audience                  VARCHAR(40) NOT NULL DEFAULT 'expired_hotspot_users',
    max_messages_per_run      INTEGER     NOT NULL DEFAULT 100,
    -- Nobody hears about the night offer more often than this
    min_days_between_messages INTEGER     NOT NULL DEFAULT 7,
    last_notified_on          DATE
);

INSERT INTO offpeak_settings (id) VALUES (1);

-- Which promotions this system opened and may therefore close again. A
-- promotion the operator started by hand is theirs, and must never be ended
-- by a scheduler that has decided the quiet hours are over.
ALTER TABLE promotions ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'MANUAL';

-- One row per person told about an offer, so a customer is not messaged about
-- the same thing twice in a week however many times the window opens.
CREATE TABLE offer_notices (
    id           BIGSERIAL   PRIMARY KEY,
    phone_number VARCHAR(20) NOT NULL,
    kind         VARCHAR(24) NOT NULL,
    sent_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_offer_notices_phone ON offer_notices (phone_number, kind, sent_at DESC);
