-- KRA eTIMS tax invoices, one per completed sale, fiscalised (signed) by the
-- EtimsService. Off by default until an operator wires their KRA credentials.
CREATE TABLE tax_invoices (
    id                  BIGSERIAL PRIMARY KEY,
    source              VARCHAR(20)    NOT NULL,
    customer_phone      VARCHAR(32),
    description         VARCHAR(255)   NOT NULL,
    amount              NUMERIC(12,2)  NOT NULL,
    status              VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    kra_invoice_number  VARCHAR(64),
    control_unit_number VARCHAR(64),
    signature           VARCHAR(128),
    qr_data             VARCHAR(512),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    signed_at           TIMESTAMPTZ
);

CREATE INDEX idx_tax_invoices_created_at ON tax_invoices (created_at DESC);
