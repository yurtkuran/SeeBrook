-- Append-only audit trail for administrative and security-relevant actions (GA-09, GA-21).
-- Owned by the platform module (ED-13). Records who did what, through which channel, why, and the
-- before/after values. Never stores secrets, tokens or payment credentials.
CREATE SCHEMA audit;

CREATE TABLE audit.event (
    id            uuid          PRIMARY KEY DEFAULT uuidv7(),
    occurred_at   timestamptz   NOT NULL DEFAULT now(),
    -- Null for platform-wide actions (e.g. Root changes).
    hoa_id        uuid          REFERENCES tenant.hoa (id),
    -- Null only for actions without a signed-in actor (e.g. bootstrapping the first Root).
    actor_user_id uuid          REFERENCES identity.user_account (id),
    channel       varchar(16)   NOT NULL,
    action        varchar(64)   NOT NULL,
    target_type   varchar(32),
    target_id     uuid,
    reason        varchar(1000),
    details       jsonb         NOT NULL DEFAULT '{}',
    CONSTRAINT event_channel_valid CHECK (channel IN ('api', 'cli', 'system')),
    CONSTRAINT event_action_not_blank CHECK (action <> '')
);

CREATE INDEX event_hoa_idx ON audit.event (hoa_id, occurred_at);
CREATE INDEX event_target_idx ON audit.event (target_type, target_id);

-- [jooq ignore start]
CREATE FUNCTION audit.prevent_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit events are append-only'
        USING ERRCODE = 'check_violation';
END
$$;

CREATE TRIGGER event_append_only
    BEFORE UPDATE OR DELETE ON audit.event
    FOR EACH ROW
EXECUTE FUNCTION audit.prevent_change();

CREATE TRIGGER event_no_truncate
    BEFORE TRUNCATE ON audit.event
    FOR EACH STATEMENT
EXECUTE FUNCTION audit.prevent_change();
-- [jooq ignore stop]
