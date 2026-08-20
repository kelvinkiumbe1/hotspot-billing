-- Two documents a business customer asks for and could not be produced.
--
-- A proforma is a priced quote with a number on it, issued BEFORE any money
-- moves. A company will not pay an ISP without one, because their own finance
-- department needs a document to raise a payment against. There was no such
-- thing here: the only way to give a business customer a number to pay against
-- was to issue a real invoice, which then sat in the arrears list and the dunning
-- queue as an unpaid debt the customer had never agreed to.
--
-- A credit note is the reverse of an invoice. Something of it existed already --
-- ledger_adjustments has a CREDIT_NOTE kind, which moves the customer's balance
-- and carries a reason -- but that is a number in a ledger and not a document.
-- It has no reference, does not say which invoice it reverses, and does not
-- reverse the VAT, which is the part a tax authority cares about.
--
-- SEPARATE TABLES, deliberately, rather than a kind column on invoices.
--
-- Adding a kind to invoices is the smaller migration and the bigger mistake.
-- Every existing query -- unpaid(), settleOldestUnpaid(), the outstanding list,
-- dunning, win-back, the revenue audit, the eTIMS submission -- selects invoices
-- without knowing a kind exists, and would silently begin treating quotes and
-- refunds as debts owed. Any one of those missed is a customer chased for money
-- they were never billed, or revenue counted twice. Two new tables cannot reach
-- any of that code.

CREATE TABLE proforma_invoices (
    id             BIGSERIAL PRIMARY KEY,
    number         VARCHAR(64) NOT NULL UNIQUE,
    subscriber_id  BIGINT NOT NULL REFERENCES subscribers(id) ON DELETE CASCADE,

    -- The same three-way split invoices carry, worked out at issue time and
    -- stored. A quote whose tax silently changed because somebody edited the VAT
    -- rate afterwards is a quote that no longer matches the paper the customer is
    -- holding.
    amount         NUMERIC(12,2) NOT NULL,
    net_amount     NUMERIC(12,2),
    vat_amount     NUMERIC(12,2),
    vat_rate       NUMERIC(5,2),
    vat_inclusive  BOOLEAN,

    months         INTEGER NOT NULL DEFAULT 1,
    description    VARCHAR(500),
    issued_on      DATE NOT NULL,
    -- Quotes expire. Without this a proforma from March gets paid in September at
    -- last year's price and somebody has to decide whether to honour it.
    valid_until    DATE NOT NULL,

    status         VARCHAR(16) NOT NULL,
    -- Set when the quote becomes a real invoice, which is the only way a proforma
    -- ever turns into money owed.
    invoice_id     BIGINT REFERENCES invoices(id) ON DELETE SET NULL,
    converted_at   TIMESTAMP WITH TIME ZONE,

    created_by     VARCHAR(120),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_proforma_subscriber ON proforma_invoices (subscriber_id);
CREATE INDEX idx_proforma_status     ON proforma_invoices (status);

CREATE TABLE credit_notes (
    id             BIGSERIAL PRIMARY KEY,
    number         VARCHAR(64) NOT NULL UNIQUE,
    subscriber_id  BIGINT NOT NULL REFERENCES subscribers(id) ON DELETE CASCADE,

    -- Which invoice this reverses. Nullable because a goodwill credit does not
    -- always answer to one, but a credit note that names its invoice is the one a
    -- tax authority and an auditor both want.
    invoice_id     BIGINT REFERENCES invoices(id) ON DELETE SET NULL,

    -- Positive amounts. The sign lives in what the document IS, not in the
    -- number, so a credit note of -2500 cannot be entered by accident and read
    -- later as a charge.
    amount         NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    net_amount     NUMERIC(12,2),
    vat_amount     NUMERIC(12,2),
    vat_rate       NUMERIC(5,2),

    reason         VARCHAR(500) NOT NULL,
    issued_on      DATE NOT NULL,

    -- The ledger row this created, so the document and the balance movement stay
    -- tied together and neither can be deleted leaving the other behind.
    adjustment_id  BIGINT,

    created_by     VARCHAR(120),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_credit_note_subscriber ON credit_notes (subscriber_id);
CREATE INDEX idx_credit_note_invoice    ON credit_notes (invoice_id);
