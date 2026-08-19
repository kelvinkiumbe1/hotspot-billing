-- Konnect: Tunisia.
--
-- Nothing here could take a Tunisian payment. Stripe does not serve Tunisia,
-- neither Paystack nor Flutterwave collects dinars, and none of the wallet rails
-- reach it. Konnect is the domestic gateway, and it covers the Konnect and
-- Flouci wallets, e-DINAR -- the post office card a great many Tunisians hold
-- and no international processor touches -- and ordinary bank cards.

ALTER TABLE payment_gateways DROP CONSTRAINT IF EXISTS payment_gateways_kind_check;
ALTER TABLE payment_gateways ADD CONSTRAINT payment_gateways_kind_check
    CHECK (kind IN ('MPESA_API', 'MPESA_PAYBILL_MANUAL', 'MPESA_TILL_MANUAL', 'BANK_TRANSFER',
                    'PAYSTACK', 'FLUTTERWAVE', 'STRIPE', 'MTN_MOMO', 'CHAPA', 'PAYNOW',
                    'AIRTEL_MONEY', 'ORANGE_MONEY', 'WAVE', 'VODACOM_MPESA', 'PAYMOB',
                    'KONNECT'));

-- No new columns:
--   secret_key -> API key, sent as x-api-key on every call
--   short_code -> receiver wallet id, which names the wallet the money lands
--                 in. Not a secret, and short_code already means exactly this
--                 for Orange Money and Vodacom.
--
-- No webhook_secret, and not because Konnect signs badly: it does not sign at
-- all. Its callback is an unsigned GET carrying a payment reference, so it is
-- treated as a hint and the status endpoint is what actually settles a payment
-- -- the same way MTN MoMo is handled.
--
-- environment picks preprod against production by host, the way Daraja and MTN
-- do rather than by a key prefix.
--
-- One thing for an operator here: the dinar has a THOUSAND millimes, not a
-- hundred. Set Currency decimals to 3 under Branding or prices will display
-- rounded and read wrong. The amount sent to Konnect is handled in code.
