-- HOA community photo (ED-57): the object key of the photo shown on the app's Home screen (a JPEG
-- the server produced from the uploaded image), and when it was set. Both NULL when there is none.
ALTER TABLE tenant.hoa
    ADD COLUMN photo_key        varchar(200),
    ADD COLUMN photo_updated_at timestamptz;

-- [jooq ignore start]
ALTER TABLE tenant.hoa
    ADD CONSTRAINT hoa_photo_complete CHECK ((photo_key IS NULL) = (photo_updated_at IS NULL));
-- [jooq ignore stop]
