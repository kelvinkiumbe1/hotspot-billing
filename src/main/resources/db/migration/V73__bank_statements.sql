-- Money that arrived at the bank.
--
-- Every rail in this system reconciles itself: a gateway calls back, quotes a
-- reference, and the payment finds its customer. A bank transfer does none of
-- that. It lands in an account with a line of narration on it and waits for
-- somebody to work out who sent it -- which is how business and corporate
-- customers pay, so it is the segment with the highest value per customer
-- getting the worst handling in the whole product.
--
-- The design principle here is that a wrong match is worse than no match.
-- Crediting the wrong customer takes two people an afternoon to unpick and
-- leaves the right customer still cut off, so only a match that is certain is
-- applied without a human; everything else waits to be confirmed by somebody
-- who can look at the narration and recognise a name.

CREATE TABLE bank_imports (
    id            BIGSERIAL PRIMARY KEY,
    filename      VARCHAR(255) NOT NULL,
    uploaded_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    uploaded_by   VARCHAR(120),
    -- Free text, whatever the operator called it. Only for telling two imports
    -- apart in a list; nothing keys off it.
    bank_name     VARCHAR(120),
    row_count     INTEGER NOT NULL DEFAULT 0,
    credit_count  INTEGER NOT NULL DEFAULT 0,
    duplicate_count INTEGER NOT NULL DEFAULT 0,
    matched_count INTEGER NOT NULL DEFAULT 0,
    applied_count INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE bank_transactions (
    id             BIGSERIAL PRIMARY KEY,
    import_id      BIGINT NOT NULL REFERENCES bank_imports(id) ON DELETE CASCADE,
    value_date     DATE,
    narration      VARCHAR(1000) NOT NULL,
    bank_reference VARCHAR(120),
    amount         NUMERIC(14,2) NOT NULL,

    -- The whole safety story of this feature.
    --
    -- Statements get re-downloaded and re-uploaded constantly: an operator pulls
    -- last month again to check something, or exports an overlapping range.
    -- Without a stable key for "this is the same transaction", the second upload
    -- credits every customer a second time. The key is a hash of the date, the
    -- amount, the narration and the bank's own reference -- the four things a
    -- bank will render identically for the same transaction and differently for
    -- two genuine transactions that happen to look alike.
    --
    -- UNIQUE across the whole table, not per import, because the point is to
    -- catch the row arriving in a DIFFERENT file.
    dedupe_key     VARCHAR(64) NOT NULL,

    status         VARCHAR(16) NOT NULL,
    -- Why we think it belongs to this customer, in words. Shown to whoever
    -- confirms it: "matched the phone number in the narration" is something a
    -- person can agree or disagree with, and a bare confidence score is not.
    match_reason   VARCHAR(255),
    subscriber_id  BIGINT,
    payment_id     BIGINT,
    decided_at     TIMESTAMP WITH TIME ZONE,
    decided_by     VARCHAR(120),

    CONSTRAINT uq_bank_txn_dedupe UNIQUE (dedupe_key)
);

CREATE INDEX idx_bank_txn_import ON bank_transactions (import_id);
CREATE INDEX idx_bank_txn_status ON bank_transactions (status);
CREATE INDEX idx_bank_txn_sub    ON bank_transactions (subscriber_id);

-- A bank transfer is not cash and is not M-Pesa, and the difference is worth
-- keeping: "how do our business customers pay" is a question somebody will ask.
--
-- The CHECK constraint has to be rebuilt rather than added to. Hibernate created
-- it from the enum, so it names the three values that existed then and would
-- reject the new one at insert time -- which is to say on the first real bank
-- payment somebody applies, long after this migration looked like it worked.
ALTER TABLE subscription_payments DROP CONSTRAINT IF EXISTS subscription_payments_method_check;
ALTER TABLE subscription_payments ADD CONSTRAINT subscription_payments_method_check
    CHECK (method IN ('MPESA', 'CASH', 'ONLINE', 'BANK'));
