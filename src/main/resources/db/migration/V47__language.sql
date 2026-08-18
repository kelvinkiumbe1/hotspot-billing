-- Currency became a setting; language did not. So an operator in Abidjan can
-- now quote francs correctly and still tell their customer "Your access code
-- is ABC123" in English, on a phone whose owner does not read English.
--
-- The staff-facing admin stays in English deliberately — it is a working tool
-- for people the operator hires and trains, and machine-translating three
-- thousand admin strings would produce a worse product, not a wider one. What
-- moves is everything a paying customer sees: the portal, the USSD menu, the
-- WhatsApp bot, and the messages sent to their phone.
ALTER TABLE portal_settings ADD COLUMN language VARCHAR(8) NOT NULL DEFAULT 'en';

-- Whether a customer's own phone or browser gets to override that. On for
-- English deployments it changes nothing; for a bilingual city — Nairobi has
-- both, Douala has both — it is the difference between one language that suits
-- half the customers and each customer reading their own.
ALTER TABLE portal_settings ADD COLUMN follow_customer_language BOOLEAN NOT NULL DEFAULT true;

-- Notification bodies are already editable per deployment, so an operator can
-- word them however they like. What they could not do is hold more than one
-- wording at a time — so a bilingual operator had to pick a language and
-- disappoint the other half of their customers.
ALTER TABLE notification_templates ADD COLUMN language VARCHAR(8) NOT NULL DEFAULT 'en';

-- The key alone was the primary key. It becomes (key, language), so the same
-- message can exist once per language.
ALTER TABLE notification_templates DROP CONSTRAINT IF EXISTS notification_templates_pkey;
ALTER TABLE notification_templates ADD PRIMARY KEY (template_key, language);
