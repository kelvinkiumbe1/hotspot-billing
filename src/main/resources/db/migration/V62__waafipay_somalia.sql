-- WaafiPay: Somalia, via Hormuud's EVC Plus.
--
-- Worth more than the population suggests. Mobile money in Somalia is more
-- universal than in Kenya, the country is full of small independent ISPs, and no
-- aggregator on the continent reaches any of them -- there was no way to take a
-- Somali payment at all.
--
-- Three things about this rail are unlike every other one here, and each was
-- confirmed against the live API rather than read in a document:
--
--   Every response is HTTP 200. A missing parameter, a refused credential and a
--   declined payment all arrive as 200 with the failure in the body. Code that
--   read the status line would treat every failure as a success.
--
--   There is no transaction-status service. Asked directly, the API recognises
--   API_PURCHASE and API_PREAUTHORIZE and answers every other service name --
--   including one invented as a control -- with the same E10309 Bad Request. So
--   the purchase response is the outcome and the payment is settled where it is
--   made or never. PaymentProvider.Charge gained a settledNow for this.
--
--   There is no token to exchange credentials for. All three ride in the body of
--   every request.

ALTER TABLE payment_gateways DROP CONSTRAINT IF EXISTS payment_gateways_kind_check;
ALTER TABLE payment_gateways ADD CONSTRAINT payment_gateways_kind_check
    CHECK (kind IN ('MPESA_API', 'MPESA_PAYBILL_MANUAL', 'MPESA_TILL_MANUAL', 'BANK_TRANSFER',
                    'PAYSTACK', 'FLUTTERWAVE', 'STRIPE', 'MTN_MOMO', 'CHAPA', 'PAYNOW',
                    'AIRTEL_MONEY', 'ORANGE_MONEY', 'WAVE', 'VODACOM_MPESA', 'PAYMOB',
                    'KONNECT', 'WAAFIPAY'));

-- No new columns:
--   short_code   -> merchantUid  (the merchant Hormuud issued)
--   consumer_key -> apiUserId
--   secret_key   -> apiKey
--
-- No environment: one address serves both, and sandbox.waafipay.net answers
-- identically to the production host -- checked -- so which credentials Hormuud
-- issued is what decides whether money moves.
--
-- The currency is USD. Somalia is dollarised in practice, EVC Plus prices in
-- dollars, and the shilling barely circulates.
