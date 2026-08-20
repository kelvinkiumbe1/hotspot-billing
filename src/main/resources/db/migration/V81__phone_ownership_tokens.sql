-- Proof that whoever is calling owns the phone number they are calling about.
--
-- isVerified() was the only thing available and it means "ever been proved":
-- a number confirmed once at signup stayed confirmed, so it authorised a credit
-- advance months later to anybody who knew the number. These columns carry a
-- fresh, single-use token issued at the moment a code is entered correctly, so
-- the proof expires and cannot be replayed.
ALTER TABLE phone_verifications
    ADD COLUMN access_token_hash  VARCHAR(128),
    ADD COLUMN access_expires_at  TIMESTAMPTZ,
    ADD COLUMN access_used_at     TIMESTAMPTZ;

-- The lookup the privileged endpoints do: one live token for a number and
-- purpose. Partial, because a spent or expired token is never looked up again.
CREATE INDEX idx_phone_access_live
    ON phone_verifications (phone_number, purpose)
    WHERE access_token_hash IS NOT NULL AND access_used_at IS NULL;
