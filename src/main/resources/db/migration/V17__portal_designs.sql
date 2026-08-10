-- Portal "designs" replace the old layout templates + colour themes.
-- Map saved legacy layout values onto the nearest new design, and drop
-- the theme column: each design now carries its own fixed look.
UPDATE hotspot_settings SET portal_template = 'MATRIX' WHERE portal_template = 'GRID';
UPDATE hotspot_settings SET portal_template = 'BREEZE' WHERE portal_template = 'MINIMAL';
ALTER TABLE hotspot_settings DROP COLUMN IF EXISTS portal_theme;
