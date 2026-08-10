-- Outbound webhooks: forward platform events to a customer endpoint, each
-- payload signed with the webhook's secret (HMAC-SHA256).
CREATE TABLE webhooks (
    id             BIGSERIAL PRIMARY KEY,
    label          VARCHAR(120) NOT NULL,
    url            VARCHAR(512) NOT NULL,
    secret         VARCHAR(120) NOT NULL,
    events         VARCHAR(512) NOT NULL,   -- comma-separated event names
    active         BOOLEAN NOT NULL DEFAULT TRUE,
    created_by     VARCHAR(120),
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_status    INTEGER,
    last_attempt_at TIMESTAMP(6) WITH TIME ZONE
);
