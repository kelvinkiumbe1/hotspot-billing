-- Loyalty programme: settings (one row) and per-phone point balances.
CREATE TABLE loyalty_settings (
    id                     BIGINT  PRIMARY KEY,
    enabled                BOOLEAN NOT NULL DEFAULT FALSE,
    points_per_hundred_kes INTEGER NOT NULL DEFAULT 10,
    points_per_minute      INTEGER NOT NULL DEFAULT 5,
    min_redeem_minutes     INTEGER NOT NULL DEFAULT 30,
    max_redeem_minutes     INTEGER NOT NULL DEFAULT 1440
);

INSERT INTO loyalty_settings (id) VALUES (1);

CREATE TABLE loyalty_accounts (
    phone_number   VARCHAR(12) PRIMARY KEY,
    points         BIGINT      NOT NULL DEFAULT 0,
    total_earned   BIGINT      NOT NULL DEFAULT 0,
    total_redeemed BIGINT      NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ
);
