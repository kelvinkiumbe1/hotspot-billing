-- A customer raises a ticket and nobody is told.
--
-- Assignment is what triggers a notification, and a ticket a customer opened
-- has no assignee — so the whole design quietly assumed an operator was
-- watching the dashboard and would triage it. Out of hours, or in a business
-- run by two people with phones in their pockets, that means the ticket sits
-- there. The technician could always pull it from the queue in the bot, but
-- nothing ever told them there was something to pull.
ALTER TABLE field_settings
    ADD COLUMN notify_technicians_on_new_ticket BOOLEAN NOT NULL DEFAULT true;
