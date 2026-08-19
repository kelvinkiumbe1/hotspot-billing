-- Vodacom M-Pesa: Tanzania, Mozambique and the DRC, off the aggregator.
--
-- Same name as Kenya's M-Pesa, different company, different platform, no shared
-- code. Tanzania is the reason: M-Pesa is the largest wallet there and the only
-- way this system could reach it was Flutterwave, paying an aggregator margin
-- on top of the wallet's own fee to reach a wallet that was there all along.
-- Mozambique is the same story, and the DRC comes with the same API.

ALTER TABLE payment_gateways DROP CONSTRAINT IF EXISTS payment_gateways_kind_check;
ALTER TABLE payment_gateways ADD CONSTRAINT payment_gateways_kind_check
    CHECK (kind IN ('MPESA_API', 'MPESA_PAYBILL_MANUAL', 'MPESA_TILL_MANUAL', 'BANK_TRANSFER',
                    'PAYSTACK', 'FLUTTERWAVE', 'STRIPE', 'MTN_MOMO', 'CHAPA', 'PAYNOW',
                    'AIRTEL_MONEY', 'ORANGE_MONEY', 'WAVE', 'VODACOM_MPESA'));

-- public_key was VARCHAR(255), which was ample for the browser-safe keys the
-- card processors issue and is not ample for an RSA public key: 2048 bits of
-- DER in base64 is about 392 characters. Saving one would have been refused by
-- the database, so this widening is not tidying -- without it the rail cannot
-- be configured at all.
ALTER TABLE payment_gateways ALTER COLUMN public_key TYPE VARCHAR(2048);

-- No other new columns. Three existing ones carry it:
--   secret_key  -> the API key from the OpenAPI portal
--   public_key  -> Vodacom's RSA public key, which the API key and then the
--                  session id are encrypted under. Not a secret, and the
--                  column already means "safe to hold in the clear".
--   short_code  -> the service provider code (the till the money lands in),
--                  which is exactly what short_code already means elsewhere.
--
-- environment decides sandbox against production, the way it does for Daraja,
-- MTN, Airtel and Orange: Vodacom picks it with a segment in the URL rather
-- than with a key prefix, so there is no prefix to read it off.
--
-- No webhook_secret, and not because Vodacom signs badly -- because it does not
-- call back at all. This is the only rail here settled entirely by asking.
