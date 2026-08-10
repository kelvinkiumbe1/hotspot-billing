-- Runtime-editable security policy, so 2FA/passkey enforcement, session
-- length and lockout can be set from the admin UI rather than env vars.
-- Single row (id = 1), seeded on first access.

CREATE TABLE security_settings (
    id                    BIGINT  PRIMARY KEY,
    require_passkeys      BOOLEAN NOT NULL DEFAULT FALSE,
    session_timeout_hours INTEGER NOT NULL DEFAULT 12,
    max_login_attempts    INTEGER NOT NULL DEFAULT 5
);
