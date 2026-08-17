-- "KES" is written into the source in seventy-five places and into the
-- frontend in a hundred and sixty more. That is fine for one operator in
-- Nairobi and fatal for the next one in Lagos or Accra, who would watch the
-- system quote their customers in a currency they do not use.
--
-- Currency becomes a property of the operator. Kenyan Shillings stay the
-- default so every existing deployment reads exactly as it does today.
ALTER TABLE portal_settings ADD COLUMN currency_code   VARCHAR(3)  NOT NULL DEFAULT 'KES';
-- What customers actually see. Separate from the code because "KES 500" is
-- how Kenya writes it while Nigeria writes "₦500" — the position and the
-- spacing differ, not just the letters.
ALTER TABLE portal_settings ADD COLUMN currency_symbol VARCHAR(8);
-- Whether the symbol leads (₦500, $5.00) or trails (500 FCFA).
ALTER TABLE portal_settings ADD COLUMN currency_suffix BOOLEAN NOT NULL DEFAULT false;
-- Shillings and naira are quoted whole; dollars and euros are not.
ALTER TABLE portal_settings ADD COLUMN currency_decimals INTEGER NOT NULL DEFAULT 0;
