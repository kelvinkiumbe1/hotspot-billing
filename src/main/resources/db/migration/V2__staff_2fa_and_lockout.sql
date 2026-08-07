-- Two-factor authentication, account lockout, and the tokens that a
-- rotating six-digit code requires.

ALTER TABLE staff_users
    -- Base32 TOTP shared secret. Present once setup has begun, but only
    -- trusted after the person has proved they can generate a code from it.
    ADD COLUMN totp_secret          VARCHAR(64),
    ADD COLUMN totp_enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN totp_confirmed_at    TIMESTAMP(6) WITH TIME ZONE,

    -- Lockout. Counted at the sign-in endpoint rather than per request:
    -- the admin loads five API calls per page, so a stale password in a
    -- browser would otherwise lock the account on a single refresh.
    ADD COLUMN failed_attempts      INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN locked_at            TIMESTAMP(6) WITH TIME ZONE,
    ADD COLUMN last_failed_at       TIMESTAMP(6) WITH TIME ZONE;

-- A signed-in session. A six-digit code that changes every thirty seconds
-- cannot be replayed on every request the way a password can, so proving
-- it once has to hand back something that can.
CREATE TABLE auth_tokens (
    id              BIGSERIAL PRIMARY KEY,
    token           VARCHAR(88)  NOT NULL UNIQUE,
    staff_user_id   BIGINT       NOT NULL REFERENCES staff_users (id) ON DELETE CASCADE,
    issued_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_used_at    TIMESTAMP(6) WITH TIME ZONE,
    -- Kept so somebody can recognise their own sessions when revoking one.
    user_agent      VARCHAR(255),
    ip_address      VARCHAR(64)
);

CREATE INDEX idx_auth_tokens_token   ON auth_tokens (token);
CREATE INDEX idx_auth_tokens_expires ON auth_tokens (expires_at);
