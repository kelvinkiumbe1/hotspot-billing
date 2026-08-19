-- CMI: Morocco.
--
-- Centre Monetique Interbancaire clears very nearly every Moroccan card, and
-- nothing else here reaches the country: Stripe does not serve Morocco and
-- neither Paystack nor Flutterwave collects dirhams.
--
-- The odd one out in this table. Every other rail is a server-to-server API;
-- CMI is a browser form post. The customer's own browser submits signed fields
-- to CMI's 3-D Secure gateway and is posted back to us with a signed result, so
-- this backend never opens a socket to CMI at all. There are two public pages
-- for it -- one that renders the self-submitting form, one that receives the
-- result -- rather than a webhook.

ALTER TABLE payment_gateways DROP CONSTRAINT IF EXISTS payment_gateways_kind_check;
ALTER TABLE payment_gateways ADD CONSTRAINT payment_gateways_kind_check
    CHECK (kind IN ('MPESA_API', 'MPESA_PAYBILL_MANUAL', 'MPESA_TILL_MANUAL', 'BANK_TRANSFER',
                    'PAYSTACK', 'FLUTTERWAVE', 'STRIPE', 'MTN_MOMO', 'CHAPA', 'PAYNOW',
                    'AIRTEL_MONEY', 'ORANGE_MONEY', 'WAVE', 'VODACOM_MPESA', 'PAYMOB',
                    'KONNECT', 'CMI'));

-- No new columns:
--   short_code -> clientid, the merchant id CMI issued
--   secret_key -> store key, which salts the hash in both directions and is
--                 therefore the whole of the security here
--
-- No webhook_secret: the store key does that job too. No environment: CMI has a
-- test gateway on a different host, and an operator switching to it would be
-- changing where their money goes, so it stays a deployment setting rather than
-- something editable in the admin.
--
-- A public address is required, unlike most rails. CMI has nothing to ask, so
-- with nowhere to be posted back to a payment could never be settled at all.
