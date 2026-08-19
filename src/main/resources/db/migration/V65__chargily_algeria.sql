-- Chargily: Algeria.
--
-- Forty-six million people and, until now, nothing. Algeria has no mobile money
-- to speak of: people hold an EDAHABIA card from Algerie Poste -- tens of
-- millions of them -- or a CIB bank card, and both clear domestically through
-- SATIM. Stripe does not serve Algeria and no pan-African aggregator collects
-- dinars, so the only way in is an Algerian gateway, and Chargily is the one
-- with a modern API rather than a bank integration project.
--
-- Endpoints confirmed live: /api/v2/checkouts and /api/v2/balance both answer
-- 401 {"message":"Unauthenticated."} while an invented path under the same
-- prefix returns Chargily's own 404 page. Authentication is checked before the
-- body, so unlike EMIS and WaafiPay the field names could not be confirmed by
-- probing -- they come from Chargily's published API.

ALTER TABLE payment_gateways DROP CONSTRAINT IF EXISTS payment_gateways_kind_check;
ALTER TABLE payment_gateways ADD CONSTRAINT payment_gateways_kind_check
    CHECK (kind IN ('MPESA_API', 'MPESA_PAYBILL_MANUAL', 'MPESA_TILL_MANUAL', 'BANK_TRANSFER',
                    'PAYSTACK', 'FLUTTERWAVE', 'STRIPE', 'MTN_MOMO', 'CHAPA', 'PAYNOW',
                    'AIRTEL_MONEY', 'ORANGE_MONEY', 'WAVE', 'VODACOM_MPESA', 'PAYMOB',
                    'KONNECT', 'WAAFIPAY', 'CMI', 'MULTICAIXA', 'CHARGILY'));

-- One column:
--   secret_key -> the API secret key, test_sk_… or live_sk_…
--
-- It does both jobs: authorises the call and verifies the webhook signature,
-- the way Paystack's key does. And because the prefix is readable, isLive() reads
-- the key rather than trusting a dropdown an operator could set to the opposite
-- of reality.
--
-- Chargily signs its callback HMAC-SHA256 over the raw body, which puts it in the
-- small group here -- with Stripe and Wave -- whose webhook can be believed on
-- its own rather than treated as a hint to go and ask.
