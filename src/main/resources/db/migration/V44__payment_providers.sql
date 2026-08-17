-- Four ways to take money, and all four are M-Pesa or a manual bank transfer.
-- That is the whole product's ceiling: an ISP in Lagos, Accra or Kampala cannot
-- use a line of this, however good the automation is.
--
-- Card and pan-African rails join it. The shape is different in one important
-- way: M-Pesa pushes a prompt to the customer's phone and calls us back, while
-- Paystack, Flutterwave and Stripe hand back a checkout URL the customer opens.
-- Both end in the same place — a webhook that settles the payment — so the rest
-- of the system does not need to know which was used.

-- The kind is guarded by a check constraint, so new rails have to be admitted.
ALTER TABLE payment_gateways DROP CONSTRAINT IF EXISTS payment_gateways_kind_check;
ALTER TABLE payment_gateways ADD CONSTRAINT payment_gateways_kind_check
    CHECK (kind IN ('MPESA_API', 'MPESA_PAYBILL_MANUAL', 'MPESA_TILL_MANUAL', 'BANK_TRANSFER',
                    'PAYSTACK', 'FLUTTERWAVE', 'STRIPE'));

-- Card processors authenticate with a secret key rather than a consumer
-- key/secret pair, publish a public key the browser may see, and sign their
-- webhooks with a secret of their own. Held separately from the Daraja fields
-- so neither set has to pretend to be the other.
ALTER TABLE payment_gateways ADD COLUMN secret_key     VARCHAR(255);
ALTER TABLE payment_gateways ADD COLUMN public_key     VARCHAR(255);
-- Stripe signs with a dedicated endpoint secret; Flutterwave compares a hash
-- you choose; Paystack signs with the secret key itself and needs nothing here.
ALTER TABLE payment_gateways ADD COLUMN webhook_secret VARCHAR(255);

-- Which rail took a payment. Not derivable afterwards once more than one is
-- configured, and reconciliation has to be able to ask.
ALTER TABLE payments ADD COLUMN provider VARCHAR(24);
