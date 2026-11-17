-- Read-only finance (M4): property accounts, imported transactions and the ledger (GA-14, GA-15,
-- GA-20, GA-21, ED-41). Owned by FinanceService (ED-13).
--
-- Money is integer cents (USD). Sign convention everywhere: positive increases what the owner
-- owes (assessments, fees), negative reduces it (payments, waivers). A balance above zero is owed;
-- below zero is a credit.
CREATE SCHEMA finance;

-- One persistent financial account per property (GA-20). It belongs to the property, not to an
-- owner, so it survives ownership transfer. external_id is the account's identifier in the HOA's
-- spreadsheet, mapped exactly and once.
CREATE TABLE finance.account (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    hoa_id      uuid        NOT NULL,
    property_id uuid        NOT NULL,
    external_id varchar(64) NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT account_hoa_scoped UNIQUE (hoa_id, id),
    CONSTRAINT account_one_per_property UNIQUE (property_id),
    CONSTRAINT account_external_id_unique UNIQUE (hoa_id, external_id),
    CONSTRAINT account_property_fk FOREIGN KEY (hoa_id, property_id) REFERENCES property.property (hoa_id, id),
    CONSTRAINT account_external_id_not_blank CHECK (btrim(external_id) <> '')
);

-- One committed transaction file (GA-14). The same file (by SHA-256) is never applied twice.
CREATE TABLE finance.import_batch (
    id              uuid          PRIMARY KEY DEFAULT uuidv7(),
    hoa_id          uuid          NOT NULL REFERENCES tenant.hoa (id),
    file_name       varchar(255)  NOT NULL,
    file_sha256     char(64)      NOT NULL,
    row_count       integer       NOT NULL,
    created_count   integer       NOT NULL,
    corrected_count integer       NOT NULL,
    unchanged_count integer       NOT NULL,
    imported_by     uuid          NOT NULL REFERENCES identity.user_account (id),
    reason          varchar(1000) NOT NULL,
    imported_at     timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT import_batch_hoa_scoped UNIQUE (hoa_id, id),
    CONSTRAINT import_batch_file_unique UNIQUE (hoa_id, file_sha256)
);

-- An imported transaction with its current values, identified by the spreadsheet's stable ID.
-- Changes arrive only as corrections in later imports, each recorded below and in the ledger.
CREATE TABLE finance.transaction (
    id               uuid         PRIMARY KEY DEFAULT uuidv7(),
    hoa_id           uuid         NOT NULL,
    account_id       uuid         NOT NULL,
    external_id      varchar(64)  NOT NULL,
    type             varchar(16)  NOT NULL,
    transaction_date date         NOT NULL,
    due_date         date,
    description      varchar(200) NOT NULL,
    amount_cents     bigint       NOT NULL,
    created_batch_id uuid         NOT NULL,
    updated_batch_id uuid         NOT NULL,
    version          integer      NOT NULL DEFAULT 1,
    CONSTRAINT transaction_hoa_scoped UNIQUE (hoa_id, id),
    CONSTRAINT transaction_external_id_unique UNIQUE (hoa_id, external_id),
    CONSTRAINT transaction_account_fk FOREIGN KEY (hoa_id, account_id) REFERENCES finance.account (hoa_id, id),
    CONSTRAINT transaction_created_batch_fk FOREIGN KEY (hoa_id, created_batch_id) REFERENCES finance.import_batch (hoa_id, id),
    CONSTRAINT transaction_updated_batch_fk FOREIGN KEY (hoa_id, updated_batch_id) REFERENCES finance.import_batch (hoa_id, id),
    CONSTRAINT transaction_type_valid CHECK (type IN ('assessment', 'late_fee', 'adjustment', 'waiver', 'payment')),
    CONSTRAINT transaction_amount_nonzero CHECK (amount_cents <> 0),
    -- Charges increase the balance; payments and waivers reduce it; adjustments go either way.
    CONSTRAINT transaction_sign_matches_type CHECK (
        (type IN ('assessment', 'late_fee') AND amount_cents > 0)
        OR (type IN ('payment', 'waiver') AND amount_cents < 0)
        OR type = 'adjustment'
    ),
    CONSTRAINT transaction_description_not_blank CHECK (btrim(description) <> '')
);

CREATE INDEX transaction_account_idx ON finance.transaction (account_id, transaction_date);

-- Immutable field-level history of every correction (GA-15).
CREATE TABLE finance.transaction_correction (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    hoa_id         uuid        NOT NULL,
    transaction_id uuid        NOT NULL,
    batch_id       uuid        NOT NULL,
    before         jsonb       NOT NULL,
    after          jsonb       NOT NULL,
    corrected_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT correction_transaction_fk FOREIGN KEY (hoa_id, transaction_id) REFERENCES finance.transaction (hoa_id, id),
    CONSTRAINT correction_batch_fk FOREIGN KEY (hoa_id, batch_id) REFERENCES finance.import_batch (hoa_id, id)
);

CREATE INDEX correction_transaction_idx ON finance.transaction_correction (transaction_id);

-- The authoritative, append-only ledger (GA-21). An account's balance is the sum of its entries.
-- An imported transaction posts one entry; correcting its amount posts a reversal of the old
-- amount and an entry for the new one, so history is never rewritten.
CREATE TABLE finance.ledger_entry (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    hoa_id         uuid        NOT NULL,
    account_id     uuid        NOT NULL,
    amount_cents   bigint      NOT NULL,
    kind           varchar(16) NOT NULL,
    transaction_id uuid        NOT NULL,
    batch_id       uuid        NOT NULL,
    posted_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ledger_account_fk FOREIGN KEY (hoa_id, account_id) REFERENCES finance.account (hoa_id, id),
    CONSTRAINT ledger_transaction_fk FOREIGN KEY (hoa_id, transaction_id) REFERENCES finance.transaction (hoa_id, id),
    CONSTRAINT ledger_batch_fk FOREIGN KEY (hoa_id, batch_id) REFERENCES finance.import_batch (hoa_id, id),
    CONSTRAINT ledger_kind_valid CHECK (kind IN ('original', 'reversal', 'correction')),
    CONSTRAINT ledger_amount_nonzero CHECK (amount_cents <> 0)
);

CREATE INDEX ledger_account_idx ON finance.ledger_entry (account_id);
CREATE INDEX ledger_transaction_idx ON finance.ledger_entry (transaction_id);

-- [jooq ignore start]
CREATE FUNCTION finance.prevent_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME
        USING ERRCODE = 'check_violation';
END
$$;

CREATE TRIGGER ledger_entry_append_only BEFORE UPDATE OR DELETE ON finance.ledger_entry
    FOR EACH ROW EXECUTE FUNCTION finance.prevent_change();
CREATE TRIGGER ledger_entry_no_truncate BEFORE TRUNCATE ON finance.ledger_entry
    FOR EACH STATEMENT EXECUTE FUNCTION finance.prevent_change();
CREATE TRIGGER correction_append_only BEFORE UPDATE OR DELETE ON finance.transaction_correction
    FOR EACH ROW EXECUTE FUNCTION finance.prevent_change();
CREATE TRIGGER correction_no_truncate BEFORE TRUNCATE ON finance.transaction_correction
    FOR EACH STATEMENT EXECUTE FUNCTION finance.prevent_change();
CREATE TRIGGER import_batch_append_only BEFORE UPDATE OR DELETE ON finance.import_batch
    FOR EACH ROW EXECUTE FUNCTION finance.prevent_change();
-- Transactions may change only through corrections; never deleted.
CREATE TRIGGER transaction_no_delete BEFORE DELETE ON finance.transaction
    FOR EACH ROW EXECUTE FUNCTION finance.prevent_change();
CREATE TRIGGER transaction_no_truncate BEFORE TRUNCATE ON finance.transaction
    FOR EACH STATEMENT EXECUTE FUNCTION finance.prevent_change();
-- [jooq ignore stop]
