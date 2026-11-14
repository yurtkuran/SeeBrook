-- Owner invitations (GA-05): single-use, expiring, bound to a verified email and one property.
-- Owned by IdentityService (ED-13). Only a SHA-256 hash of the token is stored (ED-26);
-- "expired" is derived from expires_at rather than stored.
CREATE TABLE identity.invitation (
    id          uuid         PRIMARY KEY DEFAULT uuidv7(),
    hoa_id      uuid         NOT NULL,
    property_id uuid         NOT NULL,
    -- Normalized to lower case.
    email       varchar(320) NOT NULL,
    token_hash  char(64)     NOT NULL,
    status      varchar(16)  NOT NULL DEFAULT 'pending',
    expires_at  timestamptz  NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    accepted_at timestamptz,
    accepted_by uuid         REFERENCES identity.user_account (id),
    revoked_at  timestamptz,
    CONSTRAINT invitation_token_hash_unique UNIQUE (token_hash),
    -- HOA-scoped reference: an invitation can never point at another HOA's property (ED-26).
    CONSTRAINT invitation_property_fk FOREIGN KEY (hoa_id, property_id) REFERENCES property.property (hoa_id, id),
    CONSTRAINT invitation_status_valid CHECK (status IN ('pending', 'accepted', 'revoked')),
    CONSTRAINT invitation_accepted_consistent
        CHECK ((status = 'accepted') = (accepted_at IS NOT NULL AND accepted_by IS NOT NULL)),
    CONSTRAINT invitation_revoked_consistent CHECK ((status = 'revoked') = (revoked_at IS NOT NULL)),
    CONSTRAINT invitation_email_not_blank CHECK (email <> '')
);

CREATE INDEX invitation_property_idx ON identity.invitation (hoa_id, property_id);

-- [jooq ignore start]
-- At most one pending invitation per property and email; reissuing revokes the previous one.
CREATE UNIQUE INDEX invitation_pending_unique ON identity.invitation (property_id, email) WHERE status = 'pending';
-- [jooq ignore stop]
