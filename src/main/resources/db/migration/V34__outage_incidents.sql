-- Outages as incidents rather than isolated device alerts. Three routers
-- dropping together is one event with one cause, and the customers on them
-- deserve one message with an honest ETA — not silence, and not a broadcast
-- to everybody including the people who were never affected.
CREATE TABLE incidents (
    id                   BIGSERIAL   PRIMARY KEY,
    status               VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    started_at           TIMESTAMPTZ NOT NULL,
    ended_at             TIMESTAMPTZ,
    title                VARCHAR(200) NOT NULL,
    -- Set when the customer notice went out, so it is sent once per incident
    notified_at          TIMESTAMPTZ,
    notified_count       INTEGER     NOT NULL DEFAULT 0,
    resolved_notified_at TIMESTAMPTZ,
    compensated_minutes  BIGINT      NOT NULL DEFAULT 0,
    compensated_count    INTEGER     NOT NULL DEFAULT 0,
    ticket_id            BIGINT
);
CREATE INDEX idx_incidents_status ON incidents (status, started_at DESC);

CREATE TABLE incident_routers (
    incident_id BIGINT NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    router_id   BIGINT NOT NULL,
    PRIMARY KEY (incident_id, router_id)
);

ALTER TABLE operator_alert_settings
    ADD COLUMN customer_outage_notice BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN outage_notify_after_minutes INTEGER NOT NULL DEFAULT 10,
    ADD COLUMN outage_eta_minutes INTEGER NOT NULL DEFAULT 120,
    ADD COLUMN status_page_enabled BOOLEAN NOT NULL DEFAULT true;
