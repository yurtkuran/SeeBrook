-- Owner imports (GA-05, GA-14). Owned by IdentityService (ED-13, ED-34).
--
-- An invitation now starts unsent: the HOA-verified owner (email + property) is recorded first,
-- and the token is created only when the invitation is sent, so unsent tokens never sit in the
-- database. Expiry starts at sending.
ALTER TABLE identity.invitation ALTER COLUMN token_hash DROP NOT NULL;
ALTER TABLE identity.invitation ALTER COLUMN expires_at DROP NOT NULL;
ALTER TABLE identity.invitation ADD COLUMN sent_at timestamptz;
ALTER TABLE identity.invitation ADD COLUMN invitee_name varchar(200);

-- Every invitation issued before this migration was sent when created.
UPDATE identity.invitation SET sent_at = created_at;

ALTER TABLE identity.invitation ADD CONSTRAINT invitation_sent_consistent
    CHECK ((token_hash IS NULL) = (sent_at IS NULL) AND (token_hash IS NULL) = (expires_at IS NULL));

-- One row per committed import. Re-submitting the same file for the same HOA is a no-op.
CREATE TABLE identity.owner_import (
    id                   uuid         PRIMARY KEY DEFAULT uuidv7(),
    hoa_id               uuid         NOT NULL REFERENCES tenant.hoa (id),
    file_sha256          char(64)     NOT NULL,
    file_name            varchar(255) NOT NULL,
    row_count            integer      NOT NULL,
    properties_created   integer      NOT NULL,
    invitations_created  integer      NOT NULL,
    imported_by          uuid         NOT NULL REFERENCES identity.user_account (id),
    imported_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT owner_import_file_unique UNIQUE (hoa_id, file_sha256),
    CONSTRAINT owner_import_counts_valid
        CHECK (row_count >= 0 AND properties_created >= 0 AND invitations_created >= 0)
);

ALTER TABLE identity.invitation ADD COLUMN owner_import_id uuid REFERENCES identity.owner_import (id);
