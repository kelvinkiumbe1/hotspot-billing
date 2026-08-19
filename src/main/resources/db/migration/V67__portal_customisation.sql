-- Letting an operator arrange the portal and write its words.
--
-- Two separate things an operator asked for, and they need different shapes.

-- 1. The words.
--
-- The portal ships 108 keyed strings in four languages and the backend 43 more.
-- This table overrides them one at a time. A row exists only where somebody has
-- actually typed something, and the built-in default answers everywhere else --
-- which is the whole safety design: clearing a field cannot empty the portal, a
-- language nobody in the office speaks cannot be broken by accident, and a
-- string added in a future release appears in its own words rather than as a
-- blank or a key name. Deleting a row restores the original.
--
-- Keyed on (language, key) rather than one row of JSON, so two people editing
-- different languages cannot overwrite each other, and so an audit trail says
-- which line changed rather than "the copy changed".
CREATE TABLE portal_copy (
    id          BIGSERIAL PRIMARY KEY,
    language    VARCHAR(8)    NOT NULL,
    copy_key    VARCHAR(120)  NOT NULL,
    text        VARCHAR(2000) NOT NULL,
    updated_by  VARCHAR(255),
    updated_at  TIMESTAMPTZ,
    CONSTRAINT portal_copy_unique UNIQUE (language, copy_key)
);

-- copy_key, not key: "key" is reserved in enough dialects to be a nuisance.
CREATE INDEX portal_copy_language_idx ON portal_copy (language);

-- 2. The arrangement.
--
-- Not a page builder. The six portal designs each have their own structure and a
-- free canvas would mean either abandoning them or maintaining both, so what is
-- adjustable is what can be adjusted without fighting them: which of the
-- optional blocks appear, in what order, and a handful of knobs that map onto
-- CSS variables the designs already read.
--
-- section_order is a comma-separated list of block names rather than a join
-- table. It is a single ordered list of at most a dozen fixed values, read on
-- every portal load and written rarely -- a table would be three queries and a
-- migration for something that is one string.
ALTER TABLE portal_settings ADD COLUMN section_order   VARCHAR(400);
ALTER TABLE portal_settings ADD COLUMN sections_hidden VARCHAR(400);

-- The knobs. Null everywhere means "whatever the chosen design already does",
-- so an operator who never opens this screen sees no change at all -- which is
-- the only acceptable default for a screen that sells things.
ALTER TABLE portal_settings ADD COLUMN content_align VARCHAR(16);
ALTER TABLE portal_settings ADD COLUMN corner_radius INTEGER;
ALTER TABLE portal_settings ADD COLUMN logo_size     VARCHAR(16);
ALTER TABLE portal_settings ADD COLUMN heading_font  VARCHAR(40);
ALTER TABLE portal_settings ADD COLUMN density       VARCHAR(16);
