-- Exactly one gateway could be active. Switching one on switched every other
-- one off, which is fine in Kenya where M-Pesa is effectively the only wallet
-- and wrong nearly everywhere else.
--
-- A Tanzanian ISP has customers on Vodacom M-Pesa, on Airtel Money and on Mixx.
-- Forcing a choice between them means choosing which two thirds of your market
-- cannot pay you. It is also why Malawi could take a direct Airtel rail and
-- Tanzania could not -- a limit in this system was dictating which countries it
-- could serve.
--
-- Several can be on at once now, and the customer picks.
ALTER TABLE payment_gateways ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 100;

-- The order they are offered in, and by extension which one is used when
-- nothing is chosen -- USSD and the WhatsApp bot cannot show a picker, so they
-- take the first. Existing installs keep their single active gateway as the
-- first thing customers see, so nothing changes for them.
UPDATE payment_gateways SET sort_order = 10 WHERE active = true;
