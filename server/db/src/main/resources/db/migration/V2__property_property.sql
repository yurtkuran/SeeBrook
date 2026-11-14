-- Properties (units) within an HOA. Owned by PropertyService (ED-13).
-- The property ID is immutable and HOA-scoped (GA-20). Primary keys are UUIDv7 (ED-26).
CREATE SCHEMA property;

CREATE TABLE property.property (
    id            uuid         PRIMARY KEY DEFAULT uuidv7(),
    hoa_id        uuid         NOT NULL REFERENCES tenant.hoa (id),
    unit_label    varchar(50)  NOT NULL,
    address_line1 varchar(200) NOT NULL,
    address_line2 varchar(200),
    city          varchar(100) NOT NULL,
    state_code    char(2)      NOT NULL,
    postal_code   varchar(10)  NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT property_unit_label_unique UNIQUE (hoa_id, unit_label),
    -- Target for HOA-scoped references: children use FOREIGN KEY (hoa_id, property_id)
    -- REFERENCES property.property (hoa_id, id), so a row can never point at another HOA's property (ED-26).
    CONSTRAINT property_hoa_scoped_key UNIQUE (hoa_id, id),
    CONSTRAINT property_unit_label_not_blank CHECK (unit_label <> '')
);

-- [jooq ignore start]
CREATE FUNCTION property.prevent_identity_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.hoa_id IS DISTINCT FROM OLD.hoa_id THEN
        RAISE EXCEPTION 'property id and hoa_id are immutable'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER property_identity_immutable
    BEFORE UPDATE OF id, hoa_id ON property.property
    FOR EACH ROW
EXECUTE FUNCTION property.prevent_identity_change();
-- [jooq ignore stop]
