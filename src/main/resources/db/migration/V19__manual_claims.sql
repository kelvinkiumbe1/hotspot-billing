-- Let a customer who paid by hand (Paybill/Till, or an STK whose voucher they
-- never received) claim access by entering their M-Pesa confirmation code. The
-- code is verified against Safaricom's Transaction Status API — never trusted
-- from the pasted text — so this needs an initiator name and the encrypted
-- security credential, which only the status/B2C APIs use.

ALTER TABLE payment_gateways
    ADD COLUMN initiator_name      VARCHAR(64),
    ADD COLUMN security_credential VARCHAR(2048);

-- One row per claim. receipt is UNIQUE so the same M-Pesa code can never mint
-- a second voucher, which is the whole defence against replaying a code.
CREATE TABLE manual_claims (
    id              BIGSERIAL PRIMARY KEY,
    receipt         VARCHAR(32)  NOT NULL UNIQUE,
    phone_number    VARCHAR(20)  NOT NULL,
    plan_id         BIGINT,
    conversation_id VARCHAR(64),
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    voucher_id      BIGINT,
    message         VARCHAR(255),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at     TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_manual_claims_conversation ON manual_claims (conversation_id);
