-- Fiscalisation beyond Kenya.
--
-- eTIMS was built as the only regime, so the columns are named after it: a
-- kra_invoice_number is not a thing in Lagos or Dar es Salaam. Every country
-- Zidi wants to sell into requires its own e-invoicing, and it is a legal
-- blocker rather than a nice-to-have -- an ISP cannot issue a receipt at all
-- without it. So the shape becomes general and Kenya becomes one case of it.

-- Which authority this operator files with. KRA is the default so existing
-- installs are unchanged.
ALTER TABLE tax_settings
    ADD COLUMN regime VARCHAR(16) NOT NULL DEFAULT 'KRA';

-- kra_pin holds a Kenyan PIN, a Nigerian TIN or a Tanzanian TIN depending on
-- the regime. Renamed rather than joined by a second column, because two
-- columns for one fact is how one of them ends up stale.
ALTER TABLE tax_settings RENAME COLUMN kra_pin TO tax_id;

-- The fiscal number an authority gives back. Same rename, same reason.
ALTER TABLE tax_invoices RENAME COLUMN kra_invoice_number TO fiscal_number;

ALTER TABLE tax_invoices
    ADD COLUMN regime     VARCHAR(16),
    -- Stored on the invoice rather than read from settings at display time: the
    -- rate changes, and a reprinted receipt has to show the rate it was issued
    -- under or it stops matching the customer's copy.
    ADD COLUMN vat_rate   NUMERIC(5,2),
    ADD COLUMN vat_amount NUMERIC(12,2),
    -- Where a customer or an auditor checks it. Each authority has its own.
    ADD COLUMN verify_url VARCHAR(512);

-- Everything already signed was signed under eTIMS by definition.
UPDATE tax_invoices SET regime = 'KRA' WHERE regime IS NULL;
