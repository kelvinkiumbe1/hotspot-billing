-- Two things: alerts somewhere an operator will actually see them, and proving a
-- customer's phone number is theirs.
--
-- TELEGRAM. Operator alerts go out by SMS to one number. That costs money per
-- alert, arrives in the same inbox as everything else, and on a bad night -- a
-- router down, a backup missed, capacity breached -- it is thirty texts nobody
-- reads. Telegram is free, threaded, and reachable from a laptop, which is where
-- somebody is when they act on an alert. SMS stays as well rather than being
-- replaced: it is the one channel that works when the internet is the thing that
-- broke, which is exactly when the alerts matter most.
--
-- The token is a bot token, not a personal credential. It can post to the chats
-- the bot has been added to and nothing else, so the blast radius of it leaking
-- is somebody spamming one operations chat.

ALTER TABLE messaging_settings ADD COLUMN telegram_enabled   BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE messaging_settings ADD COLUMN telegram_bot_token VARCHAR(255);
-- Where to post. A group chat id is negative, which is not a typo and is the
-- thing everybody gets wrong first.
ALTER TABLE messaging_settings ADD COLUMN telegram_chat_id   VARCHAR(64);

-- PHONE VERIFICATION. A number typed by a customer, or read down a phone by
-- somebody in a shop, is wrong often enough to matter: the renewal reminder, the
-- receipt and the voucher all go to a stranger, and the customer's first contact
-- with the business is silence. Worse for the operator, an unverified number is
-- how one person buys forty trial passes.
--
-- The code is stored HASHED. It is short-lived and low-value, but it is also a
-- credential that arrives by SMS, and a database dump that contains live codes
-- for numbers still in their window is a real if brief hole. Hashing costs
-- nothing here.
CREATE TABLE phone_verifications (
    id            BIGSERIAL PRIMARY KEY,
    phone_number  VARCHAR(32) NOT NULL,
    code_hash     VARCHAR(128) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Counted so a six-digit code cannot be walked. Cheap, and the alternative
    -- is a million guesses being a valid strategy.
    attempts      INTEGER NOT NULL DEFAULT 0,
    verified_at   TIMESTAMP WITH TIME ZONE,
    -- What the verification was for, so a code sent for one purpose cannot be
    -- spent on another.
    purpose       VARCHAR(32) NOT NULL DEFAULT 'GENERIC',
    requested_ip  VARCHAR(64)
);

-- One live challenge per number: requesting again replaces rather than stacks,
-- so a customer who taps twice does not end up with two codes and no idea which
-- one is current.
CREATE UNIQUE INDEX uq_phone_verification_live
    ON phone_verifications (phone_number, purpose) WHERE verified_at IS NULL;

CREATE INDEX idx_phone_verification_expires ON phone_verifications (expires_at);
