-- Operator alerts, outage compensation and the daily sales digest, one row.
CREATE TABLE operator_alert_settings (
    id                          BIGINT   PRIMARY KEY,
    router_offline_alert        BOOLEAN  NOT NULL DEFAULT TRUE,
    outage_compensation_enabled BOOLEAN  NOT NULL DEFAULT FALSE,
    min_outage_minutes          INTEGER  NOT NULL DEFAULT 30,
    sales_digest_enabled        BOOLEAN  NOT NULL DEFAULT FALSE,
    sales_digest_hour           INTEGER  NOT NULL DEFAULT 20,
    last_digest_sent            DATE
);

INSERT INTO operator_alert_settings (id) VALUES (1);
