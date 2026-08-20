-- A PIN for the field bot, because a phone number is not a credential.
--
-- The bot recognised a technician purely by the number a message came from, so
-- anybody holding that handset -- or a recycled SIM -- could read the whole open
-- job queue with every customer's name, address and phone, close jobs, and send
-- messages as the business. The signature guard stopped forged webhooks; it
-- cannot tell a technician from somebody holding their phone.
ALTER TABLE technicians
    ADD COLUMN chat_pin_hash      VARCHAR(200),
    ADD COLUMN chat_pin_set_at    TIMESTAMPTZ,
    ADD COLUMN chat_pin_failures  INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN chat_pin_locked_until TIMESTAMPTZ;

-- Deliberately NULL for everybody: until the office sets a PIN the field bot
-- refuses that technician and says who to ask. Nobody is using it in anger yet,
-- so there is nothing to strand, and an open queue is worse than a closed one.
