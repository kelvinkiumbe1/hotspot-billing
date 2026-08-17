-- A support ticket arrives and then waits for somebody to have a free minute
-- and the context to answer it. Most of that context — is this customer paid
-- up, is their area down right now, what fixed the last three tickets that
-- read like this one — is already in the database and simply never assembled.
--
-- So it gets assembled, and a first reply is drafted and left on the ticket
-- ready to send. Nothing here sends anything: the draft is a starting point a
-- human presses send on, edits, or ignores.
ALTER TABLE ai_settings ADD COLUMN draft_ticket_replies BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE support_tickets ADD COLUMN ai_draft        VARCHAR(2000);
ALTER TABLE support_tickets ADD COLUMN ai_drafted_at   TIMESTAMPTZ;
-- Why the draft says what it says, so the agent can check it rather than trust it
ALTER TABLE support_tickets ADD COLUMN ai_draft_basis  VARCHAR(1200);
-- Stamped whether drafting worked or not, so a failing key isn't retried forever
ALTER TABLE support_tickets ADD COLUMN ai_draft_tried_at TIMESTAMPTZ;
