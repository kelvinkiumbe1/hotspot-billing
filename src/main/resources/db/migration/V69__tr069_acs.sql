-- TR-069: managing the router in a customer's house.
--
-- The single biggest reason a fibre ISP picks Splynx over us. Without it,
-- "change my WiFi password" is a phone call where somebody talks a customer
-- through a web interface they cannot find, and a firmware rollout is a van.
--
-- CWMP is SOAP over HTTP with the conversation the wrong way round: the CPE is
-- the client and the ACS the server, but it is the ACS that gives orders. So an
-- order cannot be sent, only queued -- either the device's next Inform picks it
-- up, or a connection request pokes the device into calling now.

CREATE TABLE cpe_devices (
    id                           BIGSERIAL PRIMARY KEY,
    -- OUI plus serial is the identity TR-069 guarantees is unique, and it is what
    -- the device introduces itself with. Nobody types this anywhere: an ISP ships
    -- a box, the box calls home, the row appears.
    oui                          VARCHAR(16)  NOT NULL,
    serial_number                VARCHAR(64)  NOT NULL,
    manufacturer                 VARCHAR(120),
    product_class                VARCHAR(120),
    software_version             VARCHAR(80),
    hardware_version             VARCHAR(80),
    -- The most consequential column here. Every parameter path differs between
    -- the two data models: the WiFi password is
    -- InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.KeyPassphrase on
    -- TR-098 and Device.WiFi.AccessPoint.1.Security.KeyPassphrase on TR-181.
    -- Guess wrong and the device faults, or worse accepts and ignores it.
    data_model                   VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN',
    -- Where to poke it so a change happens now rather than at the next periodic
    -- Inform, which is typically an hour -- long enough that an operator on the
    -- phone gives up and talks the customer through the web interface anyway.
    connection_request_url       VARCHAR(300),
    connection_request_username  VARCHAR(120),
    connection_request_password  VARCHAR(120),
    remote_address               VARCHAR(64),
    last_inform_at               TIMESTAMPTZ,
    last_event                   VARCHAR(80),
    -- Set by a person or by a matching stock record, never guessed. A CPE knows
    -- its serial and billing knows customers; nothing joins the two on its own.
    subscriber_id                BIGINT,
    notes                        VARCHAR(1000),
    created_at                   TIMESTAMPTZ,
    CONSTRAINT cpe_devices_unique UNIQUE (oui, serial_number),
    CONSTRAINT cpe_devices_model_check CHECK (data_model IN ('TR098', 'TR181', 'UNKNOWN'))
);

CREATE INDEX cpe_devices_subscriber_idx ON cpe_devices (subscriber_id);
CREATE INDEX cpe_devices_last_inform_idx ON cpe_devices (last_inform_at);

CREATE TABLE cpe_tasks (
    id             BIGSERIAL PRIMARY KEY,
    cpe_device_id  BIGINT       NOT NULL,
    kind           VARCHAR(24)  NOT NULL,
    -- SENT is its own state and not a detail. A task handed to a device and never
    -- acknowledged is a different thing from one still waiting: the device may
    -- have applied it and lost the session before replying. Collapsing the two
    -- would either replay changes a customer already has, or lose ones they do not.
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    -- JSON, because the five kinds want different things -- name/value pairs, a
    -- list of names, a URL and a size -- and five sets of mostly-null columns
    -- would be worse than one field whose shape is documented per kind.
    payload        VARCHAR(4000),
    fault          VARCHAR(500),
    created_at     TIMESTAMPTZ,
    sent_at        TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,
    requested_by   VARCHAR(255),
    CONSTRAINT cpe_tasks_kind_check CHECK (kind IN
        ('SET_PARAMETERS', 'GET_PARAMETERS', 'REBOOT', 'FACTORY_RESET', 'DOWNLOAD')),
    CONSTRAINT cpe_tasks_status_check CHECK (status IN ('PENDING', 'SENT', 'DONE', 'FAILED'))
);

CREATE INDEX cpe_tasks_device_idx ON cpe_tasks (cpe_device_id);
CREATE INDEX cpe_tasks_status_idx ON cpe_tasks (status);

-- The last values a device reported, so an operator can see a WiFi name without
-- waiting for a round trip. One row per parameter per device: a data model is a
-- few hundred parameters and only the ones actually asked for are stored.
CREATE TABLE cpe_parameters (
    id             BIGSERIAL PRIMARY KEY,
    cpe_device_id  BIGINT       NOT NULL,
    name           VARCHAR(300) NOT NULL,
    value          VARCHAR(1000),
    updated_at     TIMESTAMPTZ,
    CONSTRAINT cpe_parameters_unique UNIQUE (cpe_device_id, name)
);

CREATE INDEX cpe_parameters_device_idx ON cpe_parameters (cpe_device_id);
