-- Document library (GA-10–GA-13, ED-38). Owned by DocumentService (ED-13).
-- Files live in private object storage; these tables hold metadata, versions and their checks.
-- Documents and versions are never deleted: replace, archive and restore keep history (GA-11).
CREATE SCHEMA document;

-- Single-level, HOA-scoped categories with stable IDs (GA-13).
CREATE TABLE document.category (
    id         uuid         PRIMARY KEY DEFAULT uuidv7(),
    hoa_id     uuid         NOT NULL REFERENCES tenant.hoa (id),
    name       varchar(100) NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT category_hoa_scoped UNIQUE (hoa_id, id),
    CONSTRAINT category_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE document.document (
    id                 uuid          PRIMARY KEY DEFAULT uuidv7(),
    hoa_id             uuid          NOT NULL,
    category_id        uuid          NOT NULL,
    title              varchar(200)  NOT NULL,
    description        varchar(2000) NOT NULL,
    tags               text[]        NOT NULL DEFAULT '{}',
    document_date      date,
    -- processing: first version not yet clean (never visible); published; archived (hidden, kept);
    -- rejected: the first version failed its checks.
    status             varchar(16)   NOT NULL DEFAULT 'processing',
    current_version_id uuid,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    published_at       timestamptz,
    archived_at        timestamptz,
    CONSTRAINT document_hoa_scoped UNIQUE (hoa_id, id),
    -- HOA-scoped reference: a document can never sit in another HOA's category (ED-26).
    CONSTRAINT document_category_fk FOREIGN KEY (hoa_id, category_id) REFERENCES document.category (hoa_id, id),
    CONSTRAINT document_status_valid CHECK (status IN ('processing', 'published', 'archived', 'rejected')),
    CONSTRAINT document_published_has_version CHECK (status NOT IN ('published', 'archived') OR current_version_id IS NOT NULL),
    CONSTRAINT document_archived_consistent CHECK ((status = 'archived') = (archived_at IS NOT NULL)),
    CONSTRAINT document_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT document_description_not_blank CHECK (btrim(description) <> '')
);

CREATE INDEX document_listing_idx ON document.document (hoa_id, status, category_id);

-- One uploaded PDF. Uploads land in quarantine and become clean only after validation and a
-- malware scan; rejected files are removed from storage and kept here as a record.
CREATE TABLE document.version (
    id               uuid         PRIMARY KEY DEFAULT uuidv7(),
    hoa_id           uuid         NOT NULL,
    document_id      uuid         NOT NULL,
    object_key       varchar(300) NOT NULL,
    file_name        varchar(255) NOT NULL,
    size_bytes       bigint       NOT NULL,
    sha256           char(64)     NOT NULL,
    page_count       integer      NOT NULL,
    state            varchar(16)  NOT NULL DEFAULT 'quarantined',
    rejection_reason varchar(200),
    scanned_by       varchar(200),
    scanned_at       timestamptz,
    uploaded_by      uuid         NOT NULL REFERENCES identity.user_account (id),
    uploaded_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT version_object_key_unique UNIQUE (object_key),
    CONSTRAINT version_document_fk FOREIGN KEY (hoa_id, document_id) REFERENCES document.document (hoa_id, id),
    CONSTRAINT version_state_valid CHECK (state IN ('quarantined', 'clean', 'rejected')),
    CONSTRAINT version_rejected_has_reason CHECK ((state = 'rejected') = (rejection_reason IS NOT NULL)),
    CONSTRAINT version_clean_was_scanned CHECK (state <> 'clean' OR (scanned_at IS NOT NULL AND scanned_by IS NOT NULL)),
    CONSTRAINT version_size_valid CHECK (size_bytes > 0 AND page_count > 0)
);

CREATE INDEX version_document_idx ON document.version (document_id, uploaded_at);

-- Only a clean version of the same document can be current.
ALTER TABLE document.document ADD CONSTRAINT document_current_version_fk
    FOREIGN KEY (current_version_id) REFERENCES document.version (id);

-- [jooq ignore start]
CREATE UNIQUE INDEX category_name_unique ON document.category (hoa_id, lower(name));
-- [jooq ignore stop]
