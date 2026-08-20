-- A phone number for support, and a record of every call on it.
--
-- Support currently happens on somebody's personal mobile. That has three costs
-- an operator feels but cannot see: the customer keeps ringing whoever helped
-- them last rather than whoever is on duty, the agent's own number is out in the
-- world for good, and there is no record of any of it -- no call history on the
-- customer, no way to know how long anybody waited, and nothing to listen back
-- to when a complaint turns into an argument.
--
-- Built on Africa's Talking Voice, which is the API with virtual numbers across
-- Kenya, Nigeria, Uganda, Tanzania and Rwanda. It also shares credentials with
-- the SMS integration already in this codebase, so an operator who has SMS
-- working does not enter an API key again -- only the number and a greeting.
--
-- Two things you should know before changing anything here.
--
-- First, the outbound call is TWO legs. There is no way to make a customer's
-- phone ring showing our number and have an agent's phone ring at the same
-- time; what actually happens is we ring the agent, and when they pick up we
-- bridge them to the customer. So an agent presses "call" and their own phone
-- rings first. That is not a bug and the admin says so, because an agent who
-- does not expect it assumes the feature is broken.
--
-- Second, none of this has been run against Africa's Talking. There is no
-- sandbox that will place a real call, so the request shape and the callback XML
-- are written from the API documentation and tested against a fake server at the
-- socket level. The XML half is exactly right or exactly wrong and testable
-- either way; the placing-a-call half will need one real call to confirm.

CREATE TABLE call_settings (
    id              BIGINT PRIMARY KEY,
    enabled         BOOLEAN NOT NULL DEFAULT FALSE,

    -- The number customers see and ring. Rented from Africa's Talking; there is
    -- no default that could possibly be right.
    virtual_number  VARCHAR(32),

    -- Separate host from the SMS one: voice.africastalking.com, not
    -- api.africastalking.com. Overridable because the sandbox host differs and
    -- an operator testing should not have to rebuild.
    voice_base_url  VARCHAR(255) NOT NULL DEFAULT 'https://voice.africastalking.com',

    -- Played to a caller before anybody is rung, so a customer knows the call
    -- connected rather than listening to silence and hanging up.
    greeting        VARCHAR(500),
    -- Played when every agent is busy or nobody answers.
    no_answer_message VARCHAR(500),

    -- Recording is off by default and deliberately so. A recorded call is
    -- personal data with a retention obligation attached, and turning it on
    -- should be a decision somebody makes rather than a default they inherit.
    record_calls    BOOLEAN NOT NULL DEFAULT FALSE,

    -- How long to ring one agent before moving on. Africa's Talking counts this
    -- in seconds.
    ring_seconds    INTEGER NOT NULL DEFAULT 25,

    -- The webhook the provider posts to cannot be authenticated the usual way:
    -- it is called by somebody else's server, so there is no session and no key
    -- to check. So the URL itself carries a secret, generated once, and the
    -- operator pastes the whole thing into their provider dashboard. A request
    -- with the wrong token is refused.
    --
    -- The same idea as the WhatsApp verify token already in this codebase, and
    -- better here than an IP allowlist: a voice provider's egress addresses are
    -- not published and change without notice, which would leave the operator
    -- debugging a silent phone line.
    callback_token  VARCHAR(64),

    updated_at      TIMESTAMP WITH TIME ZONE,
    updated_by      VARCHAR(120)
);

INSERT INTO call_settings (id, enabled, greeting, no_answer_message)
VALUES (1, FALSE,
        'Thank you for calling. Please hold while we connect you to support.',
        'Sorry, everyone is busy right now. Please send us a message and we will call you back.');

-- Who answers the phone.
--
-- Their own table rather than a flag on staff logins, because the people who
-- answer calls are not the same set as the people with admin accounts. A field
-- technician taking calls on a personal handset has no login and should not need
-- one to be in the rota.
CREATE TABLE call_agents (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(120) NOT NULL,
    phone_number  VARCHAR(32) NOT NULL,
    -- Lower rings first. Deliberately not unique: two agents at the same
    -- priority is a normal thing to want, and enforcing uniqueness would make
    -- reordering a rota a puzzle.
    priority      INTEGER NOT NULL DEFAULT 10,
    -- On the rota at all. Distinct from available: an agent who has gone home
    -- for the day is not active, and one who is on another call is not available
    -- but is still on the rota.
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    -- Set while a call is bridged to them and cleared when it ends, so the next
    -- caller is not rung through to somebody already talking.
    busy_until    TIMESTAMP WITH TIME ZONE,
    staff_id      BIGINT,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_call_agents_active ON call_agents (active, priority);

CREATE TABLE call_records (
    id             BIGSERIAL PRIMARY KEY,
    -- Africa's Talking session id. The one identifier that ties our record to
    -- theirs, and the key every callback arrives quoting.
    session_id     VARCHAR(120) NOT NULL UNIQUE,
    direction      VARCHAR(16) NOT NULL,

    caller_number  VARCHAR(32),
    destination_number VARCHAR(32),

    -- Who we think was on the phone. Matched on the caller's number, which is
    -- what makes the customer's account appear on screen before the agent says
    -- hello.
    subscriber_id  BIGINT,
    agent_id       BIGINT,
    ticket_id      BIGINT,

    started_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    answered_at    TIMESTAMP WITH TIME ZONE,
    ended_at       TIMESTAMP WITH TIME ZONE,
    duration_seconds INTEGER,

    status         VARCHAR(24) NOT NULL,
    -- Africa's Talking own words for how the call ended. Kept verbatim rather
    -- than mapped, because "why did this call fail" is answered by their
    -- vocabulary and a lossy translation of it helps nobody.
    hangup_cause   VARCHAR(120),
    recording_url  VARCHAR(500),
    cost           NUMERIC(10,4),
    currency       VARCHAR(8),

    -- What the agent wrote afterwards. The reason a call log is worth keeping at
    -- all: the next person to speak to this customer can see what was said.
    notes          VARCHAR(2000),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_call_records_subscriber ON call_records (subscriber_id, started_at DESC);
CREATE INDEX idx_call_records_started    ON call_records (started_at DESC);
CREATE INDEX idx_call_records_agent      ON call_records (agent_id, started_at DESC);
