-- Recurring payments (M6, ED-59): each HOA's schedule, owners' saved payment methods (held by the
-- provider; Seebrook keeps only references and a display label, GA-24), recurring plans, and one
-- row per plan and due date so a date is never charged twice.

-- When an HOA's recurring plans charge: a day of the month, and which frequencies owners may
-- choose. Quarterly plans charge in [anchor_month] and every third month after; annual plans in
-- [anchor_month]. A date on a weekend or bank holiday moves to the next business day.
CREATE TABLE finance.recurring_schedule (
    hoa_id       uuid        PRIMARY KEY REFERENCES tenant.hoa (id),
    day_of_month smallint    NOT NULL,
    anchor_month smallint    NOT NULL DEFAULT 1,
    monthly      boolean     NOT NULL,
    quarterly    boolean     NOT NULL,
    annual       boolean     NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT recurring_schedule_day CHECK (day_of_month BETWEEN 1 AND 28),
    CONSTRAINT recurring_schedule_month CHECK (anchor_month BETWEEN 1 AND 12),
    CONSTRAINT recurring_schedule_some_frequency CHECK (monthly OR quarterly OR annual)
);

-- The provider's customer for one owner at one HOA's merchant account (Stripe: a Customer on the
-- HOA's connected account). Holds no personal details.
CREATE TABLE finance.payment_customer (
    id           uuid         PRIMARY KEY DEFAULT uuidv7(),
    hoa_id       uuid         NOT NULL REFERENCES tenant.hoa (id),
    user_id      uuid         NOT NULL REFERENCES identity.user_account (id),
    provider     varchar(32)  NOT NULL,
    merchant_ref varchar(255) NOT NULL,
    customer_ref varchar(255) NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT payment_customer_one_per_owner UNIQUE (user_id, provider, merchant_ref),
    CONSTRAINT payment_customer_ref_unique UNIQUE (provider, merchant_ref, customer_ref)
);

-- An owner's request to save a payment method, so only that owner can complete it.
CREATE TABLE finance.payment_method_setup (
    id                uuid         PRIMARY KEY DEFAULT uuidv7(),
    hoa_id            uuid         NOT NULL REFERENCES tenant.hoa (id),
    user_id           uuid         NOT NULL REFERENCES identity.user_account (id),
    provider          varchar(32)  NOT NULL,
    merchant_ref      varchar(255) NOT NULL,
    customer_ref      varchar(255) NOT NULL,
    method            varchar(16)  NOT NULL,
    provider_setup_id varchar(255) NOT NULL,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT payment_method_setup_unique UNIQUE (provider, merchant_ref, provider_setup_id),
    CONSTRAINT payment_method_setup_method_valid CHECK (method IN ('card', 'ach'))
);

-- A payment method the provider keeps for an owner, usable for that owner's recurring plans at
-- this HOA. [label] is what the owner recognizes it by, e.g. "Visa •••• 4242": never a full number.
CREATE TABLE finance.saved_payment_method (
    id           uuid         PRIMARY KEY DEFAULT uuidv7(),
    hoa_id       uuid         NOT NULL REFERENCES tenant.hoa (id),
    user_id      uuid         NOT NULL REFERENCES identity.user_account (id),
    provider     varchar(32)  NOT NULL,
    merchant_ref varchar(255) NOT NULL,
    customer_ref varchar(255) NOT NULL,
    method_ref   varchar(255) NOT NULL,
    method       varchar(16)  NOT NULL,
    label        varchar(80)  NOT NULL,
    removed      boolean      NOT NULL DEFAULT false,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    removed_at   timestamptz,
    CONSTRAINT saved_payment_method_hoa_scoped UNIQUE (hoa_id, id),
    CONSTRAINT saved_payment_method_ref_unique UNIQUE (provider, merchant_ref, method_ref),
    CONSTRAINT saved_payment_method_method_valid CHECK (method IN ('card', 'ach')),
    CONSTRAINT saved_payment_method_removed_at CHECK (removed = (removed_at IS NOT NULL))
);

-- An owner's recurring payment for one property: a fixed amount the owner approved (dues + fee =
-- total, GA-23), never a sweep of the balance. One open plan per owner and property.
CREATE TABLE finance.recurring_plan (
    id              uuid         PRIMARY KEY DEFAULT uuidv7(),
    hoa_id          uuid         NOT NULL,
    account_id      uuid         NOT NULL,
    property_id     uuid         NOT NULL,
    user_id         uuid         NOT NULL REFERENCES identity.user_account (id),
    saved_method_id uuid         NOT NULL,
    frequency       varchar(16)  NOT NULL,
    dues_cents      bigint       NOT NULL,
    fee_cents       bigint       NOT NULL,
    total_cents     bigint       NOT NULL,
    status          varchar(16)  NOT NULL DEFAULT 'active',
    -- Why the plan waits for the owner (needs_approval) or ended (canceled).
    status_reason   varchar(32),
    -- The next due date to charge, before moving it to a business day; null once canceled.
    next_date       date,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    canceled_at     timestamptz,
    CONSTRAINT recurring_plan_hoa_scoped UNIQUE (hoa_id, id),
    CONSTRAINT recurring_plan_account_fk FOREIGN KEY (hoa_id, account_id) REFERENCES finance.account (hoa_id, id),
    CONSTRAINT recurring_plan_method_fk FOREIGN KEY (hoa_id, saved_method_id) REFERENCES finance.saved_payment_method (hoa_id, id),
    CONSTRAINT recurring_plan_frequency_valid CHECK (frequency IN ('monthly', 'quarterly', 'annual')),
    CONSTRAINT recurring_plan_status_valid CHECK (status IN ('active', 'paused', 'needs_approval', 'canceled')),
    CONSTRAINT recurring_plan_amounts_valid CHECK (dues_cents > 0 AND fee_cents >= 0 AND total_cents = dues_cents + fee_cents),
    CONSTRAINT recurring_plan_canceled CHECK ((status = 'canceled') = (canceled_at IS NOT NULL)),
    CONSTRAINT recurring_plan_next_date CHECK ((status = 'canceled') = (next_date IS NULL))
);

CREATE UNIQUE INDEX recurring_plan_one_open ON finance.recurring_plan (user_id, property_id) WHERE status <> 'canceled';
CREATE INDEX recurring_plan_due_idx ON finance.recurring_plan (next_date) WHERE status = 'active';

-- One row per plan and due date (GA-18): started with its payment, or skipped with the reason.
-- The unique key is what makes a date impossible to charge twice.
CREATE TABLE finance.recurring_charge (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    hoa_id         uuid        NOT NULL,
    plan_id        uuid        NOT NULL,
    scheduled_date date        NOT NULL,
    charge_date    date        NOT NULL,
    outcome        varchar(16) NOT NULL,
    skip_reason    varchar(32),
    payment_id     uuid,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT recurring_charge_once UNIQUE (plan_id, scheduled_date),
    CONSTRAINT recurring_charge_plan_fk FOREIGN KEY (hoa_id, plan_id) REFERENCES finance.recurring_plan (hoa_id, id),
    CONSTRAINT recurring_charge_outcome_valid CHECK (outcome IN ('started', 'skipped')),
    CONSTRAINT recurring_charge_outcome CHECK (
        (outcome = 'started' AND payment_id IS NOT NULL AND skip_reason IS NULL)
        OR (outcome = 'skipped' AND payment_id IS NULL AND skip_reason IS NOT NULL)
    )
);

-- A payment a recurring plan made (null for payments the owner made in the app).
ALTER TABLE finance.payment ADD COLUMN recurring_plan_id uuid;

-- [jooq ignore start]
ALTER TABLE finance.recurring_charge
    ADD CONSTRAINT recurring_charge_payment_fk FOREIGN KEY (hoa_id, payment_id) REFERENCES finance.payment (hoa_id, id);

ALTER TABLE finance.payment
    ADD CONSTRAINT payment_recurring_plan_fk FOREIGN KEY (hoa_id, recurring_plan_id) REFERENCES finance.recurring_plan (hoa_id, id);

-- Plans and charges are history: never deleted. Charges never change once written.
CREATE TRIGGER recurring_plan_no_delete BEFORE DELETE ON finance.recurring_plan
    FOR EACH ROW EXECUTE FUNCTION finance.prevent_change();
CREATE TRIGGER recurring_plan_no_truncate BEFORE TRUNCATE ON finance.recurring_plan
    FOR EACH STATEMENT EXECUTE FUNCTION finance.prevent_change();
CREATE TRIGGER recurring_charge_append_only BEFORE UPDATE OR DELETE ON finance.recurring_charge
    FOR EACH ROW EXECUTE FUNCTION finance.prevent_change();
CREATE TRIGGER recurring_charge_no_truncate BEFORE TRUNCATE ON finance.recurring_charge
    FOR EACH STATEMENT EXECUTE FUNCTION finance.prevent_change();
CREATE TRIGGER saved_payment_method_no_delete BEFORE DELETE ON finance.saved_payment_method
    FOR EACH ROW EXECUTE FUNCTION finance.prevent_change();
-- [jooq ignore stop]
