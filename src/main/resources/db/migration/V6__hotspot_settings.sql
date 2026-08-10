-- Hotspot lifecycle settings, one row (id = 1). Where to send a customer
-- after they buy, and when to auto-invalidate a voucher that was printed
-- but never used.
CREATE TABLE hotspot_settings (
    id                          BIGINT       PRIMARY KEY,
    post_purchase_redirect      VARCHAR(512),
    unused_voucher_expiry_days  INTEGER      NOT NULL DEFAULT 0
);
