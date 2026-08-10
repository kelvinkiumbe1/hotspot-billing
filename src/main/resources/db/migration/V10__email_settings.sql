-- The operator's own SMTP server, one row (id = 1), so email can be set up
-- from the admin without editing properties and restarting.
CREATE TABLE email_settings (
    id            BIGINT       PRIMARY KEY,
    enabled       BOOLEAN      NOT NULL DEFAULT FALSE,
    host          VARCHAR(255),
    port          INTEGER      NOT NULL DEFAULT 587,
    username      VARCHAR(255),
    password      VARCHAR(512),
    from_address  VARCHAR(255),
    from_name     VARCHAR(255),
    start_tls     BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_by    VARCHAR(255)
);

INSERT INTO email_settings (id, enabled, port, start_tls) VALUES (1, FALSE, 587, TRUE);
