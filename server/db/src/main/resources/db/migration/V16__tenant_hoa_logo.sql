-- HOA logo (ED-55): the object key of the HOA's current logo in private storage (a PNG the server
-- produced from the uploaded image), and when it was set. Both NULL when the HOA has no logo.
ALTER TABLE tenant.hoa
    ADD COLUMN logo_key        varchar(200),
    ADD COLUMN logo_updated_at timestamptz;

-- [jooq ignore start]
ALTER TABLE tenant.hoa
    ADD CONSTRAINT hoa_logo_complete CHECK ((logo_key IS NULL) = (logo_updated_at IS NULL));
-- [jooq ignore stop]
