-- Paymob: Egypt, the largest market on the continent and the last big one
-- nothing here could reach.
--
-- Egypt has more people than any country this system already serves, and none
-- of the rails touched it: no M-Pesa, no MTN, no Airtel, no Orange, and neither
-- Paystack nor Flutterwave nor Stripe collects Egyptian pounds. Egyptians pay by
-- telco wallet (Vodafone Cash above all), by InstaPay, by Meeza and by card, and
-- Paymob is the one integration that covers all four.

ALTER TABLE payment_gateways DROP CONSTRAINT IF EXISTS payment_gateways_kind_check;
ALTER TABLE payment_gateways ADD CONSTRAINT payment_gateways_kind_check
    CHECK (kind IN ('MPESA_API', 'MPESA_PAYBILL_MANUAL', 'MPESA_TILL_MANUAL', 'BANK_TRANSFER',
                    'PAYSTACK', 'FLUTTERWAVE', 'STRIPE', 'MTN_MOMO', 'CHAPA', 'PAYNOW',
                    'AIRTEL_MONEY', 'ORANGE_MONEY', 'WAVE', 'VODACOM_MPESA', 'PAYMOB'));

-- No new columns. Paymob needs four values and four existing columns carry them:
--   secret_key     -> API key (buys an auth token; used nowhere else)
--   webhook_secret -> HMAC secret (the only thing that makes a callback
--                     believable, and Paymob signs twenty named fields rather
--                     than the body)
--   short_code     -> integration id, which chooses which of the merchant's
--                     payment methods to charge. Not a secret, and short_code
--                     already means "the merchant's own identifier at the
--                     provider" -- the same role it plays for Orange Money.
--   public_key     -> iframe id, which is half the URL the customer opens. Also
--                     not a secret, which is what this column is for.
--
-- environment decides test against live. Unlike Paystack there is no key prefix
-- to read: Paymob's test and live API keys are both long opaque strings, so the
-- stored environment is the only answer available.
