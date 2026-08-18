-- Malawi, Tanzania and the DRC were all going through Flutterwave: an
-- aggregator fee and a checkout page for a customer who only ever wanted to
-- type a PIN. Airtel's Collections API does a USSD push, so those markets can
-- have the same flow as Kenya.
--
-- One integration, fourteen markets. Eight of them are in this system's country
-- table: Kenya, Tanzania, Uganda, Rwanda, Zambia, Malawi, DRC and Nigeria.
ALTER TABLE payment_gateways DROP CONSTRAINT IF EXISTS payment_gateways_kind_check;
ALTER TABLE payment_gateways ADD CONSTRAINT payment_gateways_kind_check
    CHECK (kind IN ('MPESA_API', 'MPESA_PAYBILL_MANUAL', 'MPESA_TILL_MANUAL', 'BANK_TRANSFER',
                    'PAYSTACK', 'FLUTTERWAVE', 'STRIPE', 'MTN_MOMO', 'CHAPA', 'PAYNOW',
                    'AIRTEL_MONEY'));

-- No new columns. Airtel authenticates with an OAuth2 client id and secret,
-- which the existing pair holds honestly:
--   consumer_key    -> client_id
--   consumer_secret -> client_secret
-- The market and its currency are not stored: they follow the operator country,
-- because they are facts about where the operator is rather than preferences.
