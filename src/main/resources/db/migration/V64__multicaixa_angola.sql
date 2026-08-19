-- Multicaixa Express: Angola, through EMIS's online payment gateway.
--
-- Angola was the last country in the table that read as supported and could
-- collect nothing: Rail.NONE, thirty-six million people, and a comment saying no
-- built rail touched it. That is the worst state for a country to be in here --
-- an operator sets their country, everything looks configured, and there is no
-- way to take a payment.
--
-- EMIS runs Multicaixa, the interbank network every Angolan card and the Express
-- wallet sit on, and it has a gateway. The paths were not in any document to
-- hand; they came out of EMIS's own browser client, which names three operations
-- on /v1/frameToken. The live API confirms all three: a request with a fake
-- merchant token gets past body validation to {"code":"104","message":"invalid
-- frame token"}, which it could not do with a field named wrongly, while an
-- invented path returns a RESTEasy "could not find resource".
--
-- Note the base path. /online-payment-gateway/portal 301s to
-- /online-payment-gateway/webframe, and the frame the customer opens is
-- /webframe/?token=... -- /webframe/frame?token=... is a 404 and was the wrong
-- guess.

ALTER TABLE payment_gateways DROP CONSTRAINT IF EXISTS payment_gateways_kind_check;
ALTER TABLE payment_gateways ADD CONSTRAINT payment_gateways_kind_check
    CHECK (kind IN ('MPESA_API', 'MPESA_PAYBILL_MANUAL', 'MPESA_TILL_MANUAL', 'BANK_TRANSFER',
                    'PAYSTACK', 'FLUTTERWAVE', 'STRIPE', 'MTN_MOMO', 'CHAPA', 'PAYNOW',
                    'AIRTEL_MONEY', 'ORANGE_MONEY', 'WAVE', 'VODACOM_MPESA', 'PAYMOB',
                    'KONNECT', 'WAAFIPAY', 'CMI', 'MULTICAIXA'));

-- One column:
--   secret_key -> the merchant frame token EMIS issues, which travels in the
--                 body of the create call rather than in a header.
--
-- No webhook_secret: EMIS does not sign its callback, so it is treated as a hint
-- and the status endpoint decides -- the same as MTN MoMo. That status endpoint
-- is why this rail is pollable at all, and it is the one thing EMIS's client
-- confirmed that no amount of guessing would have.
