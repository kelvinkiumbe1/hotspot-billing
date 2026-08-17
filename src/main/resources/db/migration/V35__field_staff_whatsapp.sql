-- Field technicians already get an SMS when a job lands on them, and that is
-- where the automation stopped: to see the job, add a note or close it they
-- had to open the Field Connect app. Most of them are standing on a ladder.
--
-- This lets the whole job run in the chat they are already in, and — more to
-- the point — lets the system chase the work rather than the office doing it:
-- a job nobody has touched gets a nudge, a job nobody has taken gets escalated.
CREATE TABLE field_settings (
    id                        BIGINT  PRIMARY KEY,
    -- Technicians can run jobs over WhatsApp from the number on their record
    whatsapp_enabled          BOOLEAN NOT NULL DEFAULT true,
    -- Assigned, but no note and no progress for this long -> nudge the tech
    stale_job_hours           INTEGER NOT NULL DEFAULT 4,
    -- Open and unassigned for this long -> tell the operator nobody has it
    unassigned_alert_minutes  INTEGER NOT NULL DEFAULT 30,
    -- A start-of-day list of what each technician is carrying
    daily_summary_enabled     BOOLEAN NOT NULL DEFAULT false,
    daily_summary_hour        INTEGER NOT NULL DEFAULT 7,
    last_summary_sent         DATE,
    -- Tell the customer their job is closed, in the technician's own words
    notify_customer_on_close  BOOLEAN NOT NULL DEFAULT true
);

INSERT INTO field_settings (id) VALUES (1);

-- Nudges are stamped on the ticket so a chase happens once, not every sweep.
ALTER TABLE support_tickets ADD COLUMN last_nudged_at   TIMESTAMPTZ;
ALTER TABLE support_tickets ADD COLUMN queue_alerted_at TIMESTAMPTZ;
