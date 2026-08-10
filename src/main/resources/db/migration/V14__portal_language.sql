-- Default captive-portal language. EN keeps the existing English wording.
ALTER TABLE hotspot_settings
    ADD COLUMN default_language VARCHAR(5) NOT NULL DEFAULT 'EN';
