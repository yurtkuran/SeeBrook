-- Property ownership: the source of an owner's access to a property (GA-07, GA-10).
-- Owned by PropertyService (ED-13). Access requires an active row (ended_at IS NULL); ending
-- ownership revokes access on the next request. Rows are never deleted, preserving history.
CREATE TABLE property.ownership (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    hoa_id      uuid        NOT NULL,
    property_id uuid        NOT NULL,
    user_id     uuid        NOT NULL REFERENCES identity.user_account (id),
    started_at  timestamptz NOT NULL DEFAULT now(),
    ended_at    timestamptz,
    -- HOA-scoped reference: an ownership can never point at another HOA's property (ED-26).
    CONSTRAINT ownership_property_fk FOREIGN KEY (hoa_id, property_id) REFERENCES property.property (hoa_id, id),
    CONSTRAINT ownership_period_valid CHECK (ended_at IS NULL OR ended_at >= started_at)
);

CREATE INDEX ownership_user_idx ON property.ownership (user_id);
CREATE INDEX ownership_property_idx ON property.ownership (hoa_id, property_id);

-- [jooq ignore start]
-- At most one active ownership per user and property.
CREATE UNIQUE INDEX ownership_active_unique ON property.ownership (property_id, user_id) WHERE ended_at IS NULL;
-- [jooq ignore stop]
