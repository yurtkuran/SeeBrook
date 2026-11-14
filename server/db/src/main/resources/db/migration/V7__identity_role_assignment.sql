-- Role assignments (GA-03): HOA-scoped admin roles, or platform-wide Root (no HOA).
-- Owned by IdentityService (ED-13). Rows are never deleted; revocation sets revoked_at.
CREATE TABLE identity.role_assignment (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    user_id    uuid        NOT NULL REFERENCES identity.user_account (id),
    role       varchar(32) NOT NULL,
    hoa_id     uuid        REFERENCES tenant.hoa (id),
    granted_at timestamptz NOT NULL DEFAULT now(),
    -- Null only for the bootstrapped first Root.
    granted_by uuid        REFERENCES identity.user_account (id),
    revoked_at timestamptz,
    revoked_by uuid        REFERENCES identity.user_account (id),
    CONSTRAINT role_assignment_role_valid
        CHECK (role IN ('root', 'hoa_admin', 'membership_admin', 'document_admin', 'financial_admin')),
    -- Root is platform-wide; every other role belongs to exactly one HOA.
    CONSTRAINT role_assignment_scope_valid CHECK ((role = 'root') = (hoa_id IS NULL)),
    CONSTRAINT role_assignment_revocation_consistent CHECK ((revoked_at IS NULL) = (revoked_by IS NULL))
);

CREATE INDEX role_assignment_user_idx ON identity.role_assignment (user_id);
CREATE INDEX role_assignment_hoa_idx ON identity.role_assignment (hoa_id, role);

-- [jooq ignore start]
-- At most one active assignment of a role per user (and HOA).
CREATE UNIQUE INDEX role_assignment_active_root_unique ON identity.role_assignment (user_id)
    WHERE role = 'root' AND revoked_at IS NULL;
CREATE UNIQUE INDEX role_assignment_active_hoa_unique ON identity.role_assignment (user_id, role, hoa_id)
    WHERE hoa_id IS NOT NULL AND revoked_at IS NULL;
-- [jooq ignore stop]
