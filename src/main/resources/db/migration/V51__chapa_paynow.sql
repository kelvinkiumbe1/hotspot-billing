-- Ethiopia and Zimbabwe were both marked as reachable by no gateway. That was
-- wrong, and wrong in the expensive direction: it tells an operator in a real
-- market that they cannot collect automatically when they can.
--
-- Chapa is Ethiopian and reaches telebirr, CBE Birr, Amole and M-Pesa
-- Ethiopia. Paynow is Zimbabwean and reaches EcoCash, OneMoney, InnBucks and
-- Zimswitch -- and its Express Checkout prompts the handset directly, so
-- Zimbabwe gets the same feel as Kenya rather than a checkout page.
ALTER TABLE payment_gateways DROP CONSTRAINT IF EXISTS payment_gateways_kind_check;
ALTER TABLE payment_gateways ADD CONSTRAINT payment_gateways_kind_check
    CHECK (kind IN ('MPESA_API', 'MPESA_PAYBILL_MANUAL', 'MPESA_TILL_MANUAL', 'BANK_TRANSFER',
                    'PAYSTACK', 'FLUTTERWAVE', 'STRIPE', 'MTN_MOMO', 'CHAPA', 'PAYNOW'));

-- No new columns again. Chapa needs a secret key and a webhook secret, which
-- both already exist. Paynow needs an integration id and an integration key:
--   consumer_key -> Integration ID
--   secret_key   -> Integration Key
-- The integration key is not only an API credential -- it is the salt in every
-- hash Paynow sends and checks -- so it lives in secret_key rather than being
-- treated as a public identifier.
