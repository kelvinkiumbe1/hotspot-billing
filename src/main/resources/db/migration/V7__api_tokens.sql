-- Long-lived personal access tokens for the REST API (Developer settings).
-- Usable as "Authorization: Bearer <token>"; resolve to the creating staff
-- member's role and permissions, same as a session token.
CREATE TABLE api_tokens (
    id             BIGSERIAL PRIMARY KEY,
    token          VARCHAR(88)  NOT NULL UNIQUE,
    name           VARCHAR(120) NOT NULL,
    staff_user_id  BIGINT       NOT NULL REFERENCES staff_users (id) ON DELETE CASCADE,
    created_by     VARCHAR(120),
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_used_at   TIMESTAMP(6) WITH TIME ZONE
);
CREATE INDEX idx_api_tokens_token ON api_tokens (token);
