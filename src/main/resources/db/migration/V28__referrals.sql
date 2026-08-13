-- Referral programme: each customer gets a shareable code; when a new customer
-- they referred makes their first purchase, both earn free-minute vouchers.
CREATE TABLE referral_settings (
    id               BIGINT PRIMARY KEY,
    enabled          BOOLEAN NOT NULL DEFAULT false,
    referrer_minutes INTEGER NOT NULL DEFAULT 60,
    referee_minutes  INTEGER NOT NULL DEFAULT 30
);

CREATE TABLE referrals (
    phone_number         VARCHAR(32) PRIMARY KEY,
    code                 VARCHAR(32) NOT NULL UNIQUE,
    successful_referrals INTEGER     NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE referral_claims (
    id             BIGSERIAL PRIMARY KEY,
    referee_phone  VARCHAR(32) NOT NULL UNIQUE,
    code           VARCHAR(32) NOT NULL,
    referrer_phone VARCHAR(32) NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at     TIMESTAMPTZ
);
CREATE INDEX idx_referral_claims_referee ON referral_claims (referee_phone);
