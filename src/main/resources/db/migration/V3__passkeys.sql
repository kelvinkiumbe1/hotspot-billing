-- Passkeys (WebAuthn). A staff member enrols a device-bound credential
-- (Windows Hello, a phone's fingerprint/face, or a security key) and signs
-- in with it afterwards. The password stays as the bootstrap and recovery
-- path — an owner resetting it clears the passkeys and forces re-enrolment.

ALTER TABLE staff_users
    -- Stable handle the authenticator ties the passkey to. base64url of 32
    -- random bytes, minted on first enrolment; never the username or id.
    ADD COLUMN webauthn_user_handle VARCHAR(64);

CREATE TABLE webauthn_credentials (
    id                 BIGSERIAL PRIMARY KEY,
    staff_user_id      BIGINT       NOT NULL REFERENCES staff_users (id) ON DELETE CASCADE,
    -- base64url credential id the browser returns on every assertion.
    credential_id      VARCHAR(512) NOT NULL UNIQUE,
    -- The CBOR attestation object, enough to rebuild the credential record
    -- (public key + AAGUID) for signature verification on each sign-in.
    attestation_object BYTEA        NOT NULL,
    -- Signature counter for clone detection. Most platform passkeys report 0
    -- and the check is skipped; kept for the authenticators that do count.
    sign_count         BIGINT       NOT NULL DEFAULT 0,
    -- A human label so a person can recognise which device this is.
    label              VARCHAR(120),
    created_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_used_at       TIMESTAMP(6) WITH TIME ZONE
);

CREATE INDEX idx_webauthn_cred_staff ON webauthn_credentials (staff_user_id);
