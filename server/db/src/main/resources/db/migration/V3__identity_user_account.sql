-- Users and their sign-in identities. Owned by IdentityService (ED-13).
-- Our user ID is authoritative; the auth provider's account ID is only an external identity (ED-28).
CREATE SCHEMA identity;

CREATE TABLE identity.user_account (
    id           uuid         PRIMARY KEY DEFAULT uuidv7(),
    -- Verified email, normalized to lower case.
    email        varchar(320) NOT NULL,
    display_name varchar(200),
    status       varchar(16)  NOT NULL DEFAULT 'active',
    created_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT user_account_email_unique UNIQUE (email),
    CONSTRAINT user_account_status_valid CHECK (status IN ('active', 'disabled')),
    CONSTRAINT user_account_email_not_blank CHECK (email <> '')
);

-- Links a provider account (e.g. Firebase UID) to our user. One user may have several.
CREATE TABLE identity.external_identity (
    id         uuid         PRIMARY KEY DEFAULT uuidv7(),
    user_id    uuid         NOT NULL REFERENCES identity.user_account (id),
    provider   varchar(32)  NOT NULL,
    subject    varchar(128) NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT external_identity_subject_unique UNIQUE (provider, subject),
    CONSTRAINT external_identity_subject_not_blank CHECK (subject <> '')
);

CREATE INDEX external_identity_user_idx ON identity.external_identity (user_id);
