-- The assistant's model was decommissioned out from under it.
--
-- llama-3.3-70b-versatile was the default from V16 and Groq has since retired
-- it. Every question came back "The model `llama-3.3-70b-versatile` does not
-- exist or you do not have access to it", so the assistant read as broken
-- rather than as pointing at something that had been withdrawn -- and the same
-- applied to the ticket-reply drafts, which simply stopped appearing.
--
-- Changing the default in the entity fixes new installs only. Existing rows
-- hold the dead string, so they are moved too. Only rows still on that exact
-- model: an operator who has deliberately chosen a different one keeps it,
-- because this is repairing an unusable default and not overriding a decision.

ALTER TABLE ai_settings ALTER COLUMN model SET DEFAULT 'openai/gpt-oss-120b';

UPDATE ai_settings
   SET model = 'openai/gpt-oss-120b'
 WHERE model = 'llama-3.3-70b-versatile';

-- gpt-oss-120b over the rest of what Groq currently serves: it is the largest
-- of the general chat models, and it returns its reasoning in a separate field.
-- The Qwen model on the same catalogue puts its chain of thought inside the
-- answer in <think> tags, which an operator would read as the assistant
-- muttering to itself. AiService strips those anyway, because the model is a
-- free-text field and somebody may well pick it.
