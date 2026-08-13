-- Win-back state: after dunning gives up and a subscriber stays lapsed, these
-- track an escalating re-engagement series (a few messages over some weeks) so
-- it runs once per lapse and stops the moment the customer comes back.
ALTER TABLE subscribers ADD COLUMN winback_cycle   TIMESTAMPTZ;
ALTER TABLE subscribers ADD COLUMN winback_stage   INTEGER NOT NULL DEFAULT 0;
ALTER TABLE subscribers ADD COLUMN winback_next_at TIMESTAMPTZ;
