-- Currency and language became settings; the country did not, so the system
-- still had no idea where it was running. Currency is a poor stand-in — XOF is
-- eight countries, USD is many — and the thing that actually differs between
-- them is how customers pay and what they call it.
--
-- The concrete symptom: "M-Pesa" is written into the customer-facing portal
-- seventy-eight times, including inside the French and Portuguese translations
-- added last week. A Ghanaian operator's customers would read "Acheter avec
-- M-Pesa" — asked to pay with something that does not exist in their country.
ALTER TABLE portal_settings ADD COLUMN country VARCHAR(8) NOT NULL DEFAULT 'KE';

-- What paying is called on a customer's screen here: "M-Pesa" in Kenya, "MTN
-- MoMo" in Ghana, "Mobile Money" where several networks compete and naming one
-- would exclude the rest, "card or bank transfer" in Nigeria. Defaults from the
-- country but overridable, because an operator knows their own market better
-- than a table does.
ALTER TABLE portal_settings ADD COLUMN payment_brand VARCHAR(40);
