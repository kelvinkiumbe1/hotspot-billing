-- Marks when a "your WiFi is almost up — buy more" WhatsApp/SMS nudge was sent
-- for an active hotspot voucher, so the reminder fires at most once per pass.
ALTER TABLE vouchers ADD COLUMN nudged_at TIMESTAMPTZ;
