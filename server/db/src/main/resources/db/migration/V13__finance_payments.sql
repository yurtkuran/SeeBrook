-- One-time payments through a payment provider (M5, ED-43): per-HOA payment settings, payments,
-- the provider events received for them, and ledger credits for settled payments.
-- Card and bank details never reach Seebrook (GA-24): a payment holds only the provider's
-- reference to it.

-- How an HOA takes payments: through which provider and merchant account (one per HOA; with Stripe
-- Connect, the HOA's connected account ID), and whether payments are suspended.
CREATE TABLE finance.payment_settings (
    hoa_id       uuid         PRIMARY KEY REFERENCES tenant.hoa (id),
    provider     varchar(32)  NOT NULL,
    merchant_ref varchar(255) NOT NULL,
    suspended    boolean      NOT NULL DEFAULT false,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT payment_settings_merchant_unique UNIQUE (provider, merchant_ref),
    CONSTRAINT payment_settings_merchant_not_blank CHECK (btrim(merchant_ref) <> '')
);

-- The payment methods an HOA accepts, each with the fee the owner pays on top of the dues
-- (GA-22): a percentage in basis points plus a fixed amount. Both 0: the HOA absorbs the fee.
CREATE TABLE finance.payment_method_fee (
    hoa_id            uuid        NOT NULL REFERENCES finance.payment_settings (hoa_id),
    method            varchar(16) NOT NULL,
    fee_basis_points  integer     NOT NULL,
    fee_fixed_cents   bigint      NOT NULL,
    PRIMARY KEY (hoa_id, method),
    CONSTRAINT payment_method_fee_method_valid CHECK (method IN ('card', 'ach')),
    CONSTRAINT payment_method_fee_range CHECK (fee_basis_points BETWEEN 0 AND 1000 AND fee_fixed_cents BETWEEN 0 AND 10000)
);

-- One payment an owner started. The amounts are exactly what the owner approved (GA-23); only
-- the dues credit the property's ledger, and only once the provider confirms it (GA-22).
CREATE TABLE finance.payment (
    id                  uuid         PRIMARY KEY DEFAULT uuidv7(),
    hoa_id              uuid         NOT NULL,
    account_id          uuid         NOT NULL,
    property_id         uuid         NOT NULL,
    user_id             uuid         NOT NULL REFERENCES identity.user_account (id),
    reference           varchar(16)  NOT NULL,
    provider            varchar(32)  NOT NULL,
    merchant_ref        varchar(255) NOT NULL,
    method              varchar(16)  NOT NULL,
    dues_cents          bigint       NOT NULL,
    fee_cents           bigint       NOT NULL,
    total_cents         bigint       NOT NULL,
    currency            char(3)      NOT NULL DEFAULT 'USD',
    status              varchar(16)  NOT NULL DEFAULT 'created',
    provider_payment_id varchar(255),
    idempotency_key     varchar(100) NOT NULL,
    failure_reason      varchar(200),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    succeeded_at        timestamptz,
    CONSTRAINT payment_hoa_scoped UNIQUE (hoa_id, id),
    CONSTRAINT payment_reference_unique UNIQUE (hoa_id, reference),
    CONSTRAINT payment_idempotency_unique UNIQUE (user_id, idempotency_key),
    CONSTRAINT payment_provider_id_unique UNIQUE (provider, merchant_ref, provider_payment_id),
    CONSTRAINT payment_account_fk FOREIGN KEY (hoa_id, account_id) REFERENCES finance.account (hoa_id, id),
    CONSTRAINT payment_method_valid CHECK (method IN ('card', 'ach')),
    CONSTRAINT payment_status_valid CHECK (status IN ('created', 'processing', 'succeeded', 'failed', 'canceled')),
    CONSTRAINT payment_amounts_valid CHECK (dues_cents > 0 AND fee_cents >= 0 AND total_cents = dues_cents + fee_cents),
    CONSTRAINT payment_succeeded_at CHECK ((status = 'succeeded') = (succeeded_at IS NOT NULL))
);

CREATE INDEX payment_account_idx ON finance.payment (account_id, created_at);

-- Every provider event received, once (GA-17): the unique event ID makes a replayed event a no-op.
-- Holds the normalized outcome only, never the raw payload.
CREATE TABLE finance.payment_event (
    id          uuid         PRIMARY KEY DEFAULT uuidv7(),
    provider    varchar(32)  NOT NULL,
    event_id    varchar(255) NOT NULL,
    event_type  varchar(64)  NOT NULL,
    hoa_id      uuid,
    payment_id  uuid,
    outcome     varchar(16)  NOT NULL,
    detail      varchar(200),
    received_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT payment_event_unique UNIQUE (provider, event_id),
    CONSTRAINT payment_event_outcome_valid CHECK (outcome IN ('applied', 'unchanged', 'ignored', 'unmatched', 'mismatch'))
);

-- A settled payment credits the ledger through its own entry, never through an imported transaction.
ALTER TABLE finance.ledger_entry ADD COLUMN payment_id uuid;

-- [jooq ignore start]
ALTER TABLE finance.payment_event
    ADD CONSTRAINT payment_event_payment_fk FOREIGN KEY (hoa_id, payment_id) REFERENCES finance.payment (hoa_id, id);

ALTER TABLE finance.ledger_entry
    ALTER COLUMN transaction_id DROP NOT NULL,
    ALTER COLUMN batch_id DROP NOT NULL,
    DROP CONSTRAINT ledger_kind_valid,
    ADD CONSTRAINT ledger_kind_valid CHECK (kind IN ('original', 'reversal', 'correction', 'payment')),
    ADD CONSTRAINT ledger_payment_fk FOREIGN KEY (hoa_id, payment_id) REFERENCES finance.payment (hoa_id, id),
    -- Each entry comes from exactly one source: an imported transaction (with its batch) or a payment.
    ADD CONSTRAINT ledger_one_source CHECK (
        (kind <> 'payment' AND transaction_id IS NOT NULL AND batch_id IS NOT NULL AND payment_id IS NULL)
        OR (kind = 'payment' AND payment_id IS NOT NULL AND transaction_id IS NULL AND batch_id IS NULL AND amount_cents < 0)
    );

-- A payment credits the ledger at most once, whatever happens to its events.
CREATE UNIQUE INDEX ledger_payment_once ON finance.ledger_entry (payment_id) WHERE kind = 'payment';

-- Payments and their events are never deleted; settings and fees may change (audited).
CREATE TRIGGER payment_no_delete BEFORE DELETE ON finance.payment
    FOR EACH ROW EXECUTE FUNCTION finance.prevent_change();
CREATE TRIGGER payment_no_truncate BEFORE TRUNCATE ON finance.payment
    FOR EACH STATEMENT EXECUTE FUNCTION finance.prevent_change();
CREATE TRIGGER payment_event_append_only BEFORE UPDATE OR DELETE ON finance.payment_event
    FOR EACH ROW EXECUTE FUNCTION finance.prevent_change();
CREATE TRIGGER payment_event_no_truncate BEFORE TRUNCATE ON finance.payment_event
    FOR EACH STATEMENT EXECUTE FUNCTION finance.prevent_change();
-- [jooq ignore stop]
