-- Paystack, Flutterwave and Stripe all reach mobile money, but they do it
-- through a checkout page: the customer leaves the portal, picks a wallet,
-- types their number, and only then gets a prompt. M-Pesa does not work that
-- way and neither does the product built around it.
--
-- MTN MoMo does not either. Its RequestToPay is functionally STK Push -- the
-- customer gets a prompt and enters a PIN, with no page in between -- so the
-- flow already built works unchanged across Ghana, Uganda, Rwanda, Zambia,
-- Cameroon and Cote d'Ivoire on one integration.
ALTER TABLE payment_gateways DROP CONSTRAINT IF EXISTS payment_gateways_kind_check;
ALTER TABLE payment_gateways ADD CONSTRAINT payment_gateways_kind_check
    CHECK (kind IN ('MPESA_API', 'MPESA_PAYBILL_MANUAL', 'MPESA_TILL_MANUAL', 'BANK_TRANSFER',
                    'PAYSTACK', 'FLUTTERWAVE', 'STRIPE', 'MTN_MOMO'));

-- No new columns. MTN needs three credentials and the existing ones fit
-- honestly: the API user and API key really are a key pair, and the
-- subscription key really is the secret that authenticates the app.
--   consumer_key    -> API User (a UUID from the MoMo developer portal)
--   consumer_secret -> API Key
--   secret_key      -> Ocp-Apim-Subscription-Key
-- The target market is not stored at all: it follows the operator country,
-- because it is a fact about where they are rather than a preference, and a
-- mistyped one fails every charge without saying why.
