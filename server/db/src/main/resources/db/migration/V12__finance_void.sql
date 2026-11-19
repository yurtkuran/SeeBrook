-- Voided transactions (ED-42). The HOA's spreadsheet voids a transaction by keeping its row and
-- setting the amount to 0. An import records that as a correction: the transaction keeps its
-- history, its amount becomes 0 and the ledger gets a reversal of the old amount. A transaction can
-- reach 0 only through a correction (version > 1); it is never created at 0.
-- [jooq ignore start]
ALTER TABLE finance.transaction DROP CONSTRAINT transaction_amount_nonzero;
ALTER TABLE finance.transaction DROP CONSTRAINT transaction_sign_matches_type;

ALTER TABLE finance.transaction
    ADD CONSTRAINT transaction_zero_only_when_voided CHECK (amount_cents <> 0 OR version > 1);

-- Charges increase the balance; payments and waivers reduce it; adjustments go either way; 0 is void.
ALTER TABLE finance.transaction
    ADD CONSTRAINT transaction_sign_matches_type CHECK (
        amount_cents = 0
        OR (type IN ('assessment', 'late_fee') AND amount_cents > 0)
        OR (type IN ('payment', 'waiver') AND amount_cents < 0)
        OR type = 'adjustment'
    );
-- [jooq ignore stop]
