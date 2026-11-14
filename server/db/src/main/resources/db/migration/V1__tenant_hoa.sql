-- Tenant root: one row per HOA (GA-01, GA-06). Owned by TenantService (ED-13). Primary keys are UUIDv7 (ED-26).
CREATE SCHEMA tenant;

CREATE TABLE tenant.hoa (
    id         uuid         PRIMARY KEY DEFAULT uuidv7(),
    slug       varchar(64)  NOT NULL,
    name       varchar(200) NOT NULL,
    time_zone  varchar(64)  NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT hoa_slug_unique UNIQUE (slug),
    CONSTRAINT hoa_name_not_blank CHECK (name <> ''),
    CONSTRAINT hoa_time_zone_not_blank CHECK (time_zone <> '')
);

-- [jooq ignore start]
ALTER TABLE tenant.hoa
    ADD CONSTRAINT hoa_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$');
-- [jooq ignore stop]
