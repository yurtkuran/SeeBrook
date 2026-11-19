-- Reconciliation of unsettled payments and automatic suspension during provider outages (M5,
-- ED-46). Owned by FinanceService (ED-13).

-- Provider health per HOA, apart from the administrator's hold (`suspended`): consecutive calls
-- the provider could not answer, and since when payments are paused because of them. Cleared
-- automatically once the provider answers again; `suspended` never is.
ALTER TABLE finance.payment_settings
    ADD COLUMN provider_failures integer NOT NULL DEFAULT 0,
    ADD COLUMN outage_since timestamptz,
    ADD CONSTRAINT payment_settings_failures_valid CHECK (provider_failures >= 0);

-- When Seebrook last asked the provider about an unsettled payment, so each one is checked
-- at a steady pace rather than on every pass.
ALTER TABLE finance.payment ADD COLUMN reconciled_at timestamptz;

CREATE INDEX payment_unsettled_idx ON finance.payment (hoa_id, updated_at)
    WHERE status IN ('created', 'processing', 'failed');
