-- "Lipa Baadaye" — WiFi on credit. A customer with a good payment record gets
-- online now and settles on their next purchase, which is added to that
-- purchase's M-Pesa amount so the debt is always visible and always recovered.
CREATE TABLE credit_settings (
    id                    BIGINT  PRIMARY KEY,
    enabled               BOOLEAN NOT NULL DEFAULT false,
    min_purchases         INTEGER NOT NULL DEFAULT 3,
    min_days_known        INTEGER NOT NULL DEFAULT 7,
    max_advance           NUMERIC(10, 2) NOT NULL DEFAULT 100,
    fee_percent           INTEGER NOT NULL DEFAULT 0,
    repay_within_hours    INTEGER NOT NULL DEFAULT 48,
    -- Missed repayments before a customer is cut off from credit for good.
    max_defaults          INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE credit_advances (
    id             BIGSERIAL      PRIMARY KEY,
    phone_number   VARCHAR(32)    NOT NULL,
    plan_id        BIGINT,
    voucher_code   VARCHAR(64),
    amount         NUMERIC(10, 2) NOT NULL,
    fee            NUMERIC(10, 2) NOT NULL DEFAULT 0,
    total_due      NUMERIC(10, 2) NOT NULL,
    status         VARCHAR(16)    NOT NULL DEFAULT 'OUTSTANDING',
    issued_at      TIMESTAMPTZ    NOT NULL,
    due_at         TIMESTAMPTZ    NOT NULL,
    repaid_at      TIMESTAMPTZ,
    repaid_note    VARCHAR(300),
    reminded_at    TIMESTAMPTZ
);
CREATE INDEX idx_credit_advances_phone ON credit_advances (phone_number, status);
CREATE INDEX idx_credit_advances_status ON credit_advances (status, due_at);
