-- The payer's number now comes from the verified Transaction Status result
-- (Safaricom's own record of who paid), not from a field the customer types,
-- so a claim starts with no phone and fills it in when the result arrives.
ALTER TABLE manual_claims ALTER COLUMN phone_number DROP NOT NULL;
