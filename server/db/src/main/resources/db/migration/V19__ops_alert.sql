-- Operator alerts (ED-61). Owned by the platform module (ED-13).
--
-- Something an operator must look at: payments paused by a provider outage, a payment event that
-- doesn't match, a background job that gave up, work stuck longer than it should be. One open
-- alert per key: a repeat counts as another occurrence rather than another alert, so an operator is
-- emailed once per problem. Summaries and details hold identifiers, references and counts only —
-- never names, email addresses, amounts tied to a person, or secrets.
CREATE SCHEMA ops;

CREATE TABLE ops.alert (
    id            uuid          PRIMARY KEY DEFAULT uuidv7(),
    -- What happened, e.g. `payments_outage`, `payment_mismatch`, `job_failed`.
    kind          varchar(64)   NOT NULL,
    -- The problem this alert is about, e.g. `payments_outage:<hoa>`; one open alert per key.
    alert_key     varchar(200)  NOT NULL,
    hoa_id        uuid          REFERENCES tenant.hoa (id),
    summary       varchar(500)  NOT NULL,
    details       jsonb         NOT NULL DEFAULT '{}',
    occurrences   integer       NOT NULL DEFAULT 1,
    opened_at     timestamptz   NOT NULL DEFAULT now(),
    last_seen_at  timestamptz   NOT NULL DEFAULT now(),
    resolved_at   timestamptz,
    -- Null when it resolved by itself (the outage or backlog cleared).
    resolved_by   uuid          REFERENCES identity.user_account (id),
    resolution    varchar(1000),
    CONSTRAINT alert_kind_not_blank CHECK (kind <> ''),
    CONSTRAINT alert_occurrences_valid CHECK (occurrences >= 1),
    CONSTRAINT alert_resolution_consistent CHECK (resolved_at IS NOT NULL OR (resolved_by IS NULL AND resolution IS NULL))
);

CREATE INDEX alert_opened_idx ON ops.alert (opened_at);

-- [jooq ignore start]
CREATE UNIQUE INDEX alert_open_unique ON ops.alert (alert_key) WHERE resolved_at IS NULL;

-- Alerts are history: never deleted.
CREATE FUNCTION ops.prevent_delete() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ops.alert rows are never deleted';
END;
$$;

CREATE TRIGGER alert_no_delete BEFORE DELETE ON ops.alert
    FOR EACH ROW EXECUTE FUNCTION ops.prevent_delete();
-- [jooq ignore stop]
