-- Which captive-portal layout customers see. CLASSIC keeps the existing look.
ALTER TABLE hotspot_settings
    ADD COLUMN portal_template VARCHAR(20) NOT NULL DEFAULT 'CLASSIC';
