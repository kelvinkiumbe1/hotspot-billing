-- Credentials a CPE must present before the ACS will talk to it.
--
-- /acs was reachable by anyone: a forged Inform registered a device, and asking
-- for orders handed over whatever the operator had queued for that serial and
-- marked it sent, so the real box never got it. TR-069 has no notion of a
-- session, so the device carries HTTP Basic credentials the operator puts into
-- its provisioning template -- one shared pair, because a CPE is configured
-- before anybody knows its serial.
CREATE TABLE acs_settings (
    id             BIGINT PRIMARY KEY,
    username       VARCHAR(64),
    password_hash  VARCHAR(200),
    -- Off means an unknown serial is refused rather than filed. On is how a new
    -- estate is brought in: the credentials still have to be right.
    allow_unknown  BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by     VARCHAR(255),
    CONSTRAINT acs_settings_single_row CHECK (id = 1)
);

-- Deliberately no credentials: until an operator sets a pair, the ACS refuses
-- every request. Nothing is registered yet, so there is nothing to strand, and
-- a silent open door is worse than a device that cannot check in.
INSERT INTO acs_settings (id, username, password_hash) VALUES (1, NULL, NULL);
