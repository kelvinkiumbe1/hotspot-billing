-- Zero-touch PayBill activation: a customer sends money to the paybill with
-- no STK prompt and no app, and the pass is issued (and their device let on)
-- automatically. The pay code is the per-device account number the captive
-- portal tells them to type, which is how a payment is tied to a device.
CREATE TABLE paybill_settings (
    id                  BIGINT  PRIMARY KEY,
    enabled             BOOLEAN NOT NULL DEFAULT true,
    auto_login_by_mac   BOOLEAN NOT NULL DEFAULT false,
    pay_code_minutes    INTEGER NOT NULL DEFAULT 120,
    notify_on_shortfall BOOLEAN NOT NULL DEFAULT true,
    -- Above this, don't guess: a large payment from somebody the system does
    -- not know is more likely a mistake than a hotspot purchase.
    max_amount          NUMERIC(10, 2) NOT NULL DEFAULT 3000
);

CREATE TABLE pay_codes (
    code         VARCHAR(16)  PRIMARY KEY,
    mac_address  VARCHAR(32),
    router_id    BIGINT,
    voucher_code VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    used_at      TIMESTAMPTZ
);
CREATE INDEX idx_pay_codes_mac ON pay_codes (mac_address);
CREATE INDEX idx_pay_codes_expires ON pay_codes (expires_at);
