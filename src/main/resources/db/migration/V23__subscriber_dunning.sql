-- Dunning state for smart failed-payment recovery. When an auto-renewal STK
-- isn't paid, these track the retry cycle so the app re-prompts on an escalating
-- schedule until the customer pays or the attempts are exhausted.
ALTER TABLE subscribers ADD COLUMN dunning_cycle    TIMESTAMPTZ;
ALTER TABLE subscribers ADD COLUMN dunning_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE subscribers ADD COLUMN dunning_next_at  TIMESTAMPTZ;
