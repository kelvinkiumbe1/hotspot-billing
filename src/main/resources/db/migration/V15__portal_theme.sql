-- Captive-portal visual theme. AMBER keeps the existing look.
ALTER TABLE hotspot_settings
    ADD COLUMN portal_theme VARCHAR(20) NOT NULL DEFAULT 'AMBER';
