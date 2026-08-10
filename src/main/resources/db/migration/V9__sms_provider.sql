-- Which SMS gateway is active. The three credential columns are reused per
-- provider (Africa's Talking: username/apiKey/senderId; Twilio: Account SID/
-- Auth Token/From number), so only the selector is new.
ALTER TABLE messaging_settings
    ADD COLUMN sms_provider VARCHAR(40) NOT NULL DEFAULT 'AFRICASTALKING';
