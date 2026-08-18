-- Recurring collection outside Kenya.
--
-- Two things were Kenya-only, and the second is the one that mattered.
--
-- First, a PPPoE renewal could only be charged by Daraja STK: initiateStk
-- called MpesaService directly rather than going through the provider
-- abstraction, so a Ghanaian ISP could configure MTN MoMo perfectly and still
-- have no way to bill a monthly customer.
--
-- Second, the only standing order was M-Pesa Ratiba. Everywhere else, every
-- renewal needed the customer to act -- which is why the dunning, win-back,
-- nudge and auto-STK machinery exists at all. All four are recovery for a
-- payment a mandate would simply have collected.
--
-- Ratiba and a stored token are genuinely different consents and are modelled
-- as such. Ratiba PUSHES: the customer approves on their handset and Safaricom
-- sends the money on schedule; we only record it arriving. A token is PULLED:
-- the customer authorised us once and we initiate each charge. Conflating them
-- would either chase a customer whose money is already coming, or sit waiting
-- for money nobody is going to send.

-- --- Subscription payments can come from any rail now ---
ALTER TABLE subscription_payments
    ADD COLUMN IF NOT EXISTS provider VARCHAR(24);

-- Everything already in the table was Daraja, by construction.
UPDATE subscription_payments SET provider = 'MPESA_API'
    WHERE provider IS NULL AND method = 'MPESA';

ALTER TABLE subscription_payments DROP CONSTRAINT IF EXISTS subscription_payments_method_check;
ALTER TABLE subscription_payments ADD CONSTRAINT subscription_payments_method_check
    CHECK (method IN ('MPESA', 'CASH', 'ONLINE'));

-- --- Mandates ---
ALTER TABLE payment_mandates DROP CONSTRAINT IF EXISTS payment_mandates_provider_check;

-- PUSH: the provider sends money on its own schedule (Ratiba).
-- PULL: we charge a stored authorisation when the renewal falls due.
ALTER TABLE payment_mandates
    ADD COLUMN IF NOT EXISTS model VARCHAR(8) NOT NULL DEFAULT 'PUSH';
UPDATE payment_mandates SET model = 'PUSH' WHERE provider = 'MPESA_API';
ALTER TABLE payment_mandates ADD CONSTRAINT payment_mandates_model_check
    CHECK (model IN ('PUSH', 'PULL'));

-- The reusable authorisation itself. Paystack calls it an authorization code,
-- Flutterwave a card token, Stripe a payment method id. Never returned by any
-- API this system exposes: it is the thing that can move a customer's money.
ALTER TABLE payment_mandates
    ADD COLUMN IF NOT EXISTS token VARCHAR(255);

-- Consent, recorded rather than assumed. A stored authorisation with no record
-- of who agreed to it and against which payment is one the operator cannot
-- defend if the customer disputes a charge -- and a chargeback on a card rail
-- is decided on exactly that evidence.
ALTER TABLE payment_mandates
    ADD COLUMN IF NOT EXISTS consented_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS consent_reference VARCHAR(120),
    ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS consecutive_failures INTEGER NOT NULL DEFAULT 0;

-- A PULL mandate that has been asked for and not yet authorised has no token.
-- The status already says PENDING; this makes the pairing checkable.
CREATE INDEX IF NOT EXISTS idx_mandates_due
    ON payment_mandates (status, model);
