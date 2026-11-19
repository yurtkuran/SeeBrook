-- Refunds, failed refunds, and disputes (including ACH returns) of received payments (M5, ED-45,
-- GA-19). The original payment is never changed: each money movement is its own record with a
-- linked compensating ledger entry. Owned by FinanceService (ED-13).
CREATE TABLE finance.payment_adjustment (
    id           uuid         PRIMARY KEY DEFAULT uuidv7(),
    hoa_id       uuid         NOT NULL,
    payment_id   uuid         NOT NULL,
    kind         varchar(24)  NOT NULL,
    -- The provider's refund or dispute ID: one record per kind and provider object.
    provider_ref varchar(255) NOT NULL,
    -- For the bookkeeper and the register: PAY-XXXXXXXX-R1, -R1F, -D1, -D1W (ED-45).
    reference    varchar(24)  NOT NULL,
    -- The money that moved, as the provider reports it (dues and fee together); always positive.
    amount_cents bigint       NOT NULL,
    -- The effect on what the owner owes: positive when money went back to the owner, negative when it came back.
    dues_cents   bigint       NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT payment_adjustment_hoa_scoped UNIQUE (hoa_id, id),
    CONSTRAINT payment_adjustment_once UNIQUE (payment_id, kind, provider_ref),
    CONSTRAINT payment_adjustment_reference_unique UNIQUE (hoa_id, reference),
    CONSTRAINT payment_adjustment_payment_fk FOREIGN KEY (hoa_id, payment_id) REFERENCES finance.payment (hoa_id, id),
    CONSTRAINT payment_adjustment_kind_valid CHECK (kind IN ('refund', 'refund_failed', 'dispute_withdrawn', 'dispute_reinstated')),
    CONSTRAINT payment_adjustment_amount_positive CHECK (amount_cents > 0),
    CONSTRAINT payment_adjustment_direction CHECK (
        (kind IN ('refund', 'dispute_withdrawn') AND dues_cents >= 0)
        OR (kind IN ('refund_failed', 'dispute_reinstated') AND dues_cents <= 0)
    )
);

CREATE INDEX payment_adjustment_payment_idx ON finance.payment_adjustment (payment_id);

ALTER TABLE finance.ledger_entry ADD COLUMN payment_adjustment_id uuid;

-- [jooq ignore start]
ALTER TABLE finance.ledger_entry
    DROP CONSTRAINT ledger_kind_valid,
    ADD CONSTRAINT ledger_kind_valid CHECK (kind IN ('original', 'reversal', 'correction', 'payment', 'payment_reversal', 'payment_restored')),
    ADD CONSTRAINT ledger_payment_adjustment_fk FOREIGN KEY (hoa_id, payment_adjustment_id) REFERENCES finance.payment_adjustment (hoa_id, id),
    DROP CONSTRAINT ledger_one_source,
    -- Each entry has exactly one source: an imported transaction, a payment, or an adjustment of one.
    ADD CONSTRAINT ledger_one_source CHECK (
        (kind IN ('original', 'reversal', 'correction') AND transaction_id IS NOT NULL AND batch_id IS NOT NULL
            AND payment_id IS NULL AND payment_adjustment_id IS NULL)
        OR (kind = 'payment' AND payment_id IS NOT NULL AND payment_adjustment_id IS NULL
            AND transaction_id IS NULL AND batch_id IS NULL AND amount_cents < 0)
        OR (kind = 'payment_reversal' AND payment_id IS NOT NULL AND payment_adjustment_id IS NOT NULL
            AND transaction_id IS NULL AND batch_id IS NULL AND amount_cents > 0)
        OR (kind = 'payment_restored' AND payment_id IS NOT NULL AND payment_adjustment_id IS NOT NULL
            AND transaction_id IS NULL AND batch_id IS NULL AND amount_cents < 0)
    );

CREATE UNIQUE INDEX ledger_payment_adjustment_once ON finance.ledger_entry (payment_adjustment_id) WHERE payment_adjustment_id IS NOT NULL;

CREATE TRIGGER payment_adjustment_append_only BEFORE UPDATE OR DELETE ON finance.payment_adjustment
    FOR EACH ROW EXECUTE FUNCTION finance.prevent_change();
CREATE TRIGGER payment_adjustment_no_truncate BEFORE TRUNCATE ON finance.payment_adjustment
    FOR EACH STATEMENT EXECUTE FUNCTION finance.prevent_change();
-- [jooq ignore stop]
