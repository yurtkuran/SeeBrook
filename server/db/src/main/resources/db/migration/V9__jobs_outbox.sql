-- Background jobs (ED-06, ED-35). Owned by the platform module (ED-13).
--
-- A job is written in the same transaction as the change that needs it, then run by the in-process
-- worker with retries. Payloads hold identifiers only — never email addresses, tokens or other
-- personal or secret data.
CREATE SCHEMA jobs;

CREATE TABLE jobs.outbox_job (
    id           uuid          PRIMARY KEY DEFAULT uuidv7(),
    -- `<service>.<job>`, e.g. identity.send_invitation.
    kind         varchar(100)  NOT NULL,
    hoa_id       uuid          REFERENCES tenant.hoa (id),
    -- At most one queued or running job per key, e.g. `invitation:<id>`.
    dedupe_key   varchar(200)  NOT NULL,
    payload      jsonb         NOT NULL DEFAULT '{}',
    status       varchar(16)   NOT NULL DEFAULT 'queued',
    attempts     integer       NOT NULL DEFAULT 0,
    run_after    timestamptz   NOT NULL DEFAULT now(),
    -- Set while running; a job whose lock has lapsed (the worker died) is claimed again.
    locked_until timestamptz,
    -- Exception type of the last failure only; messages may contain personal data.
    last_error   varchar(200),
    created_at   timestamptz   NOT NULL DEFAULT now(),
    finished_at  timestamptz,
    CONSTRAINT outbox_job_status_valid CHECK (status IN ('queued', 'running', 'done', 'failed', 'cancelled')),
    CONSTRAINT outbox_job_attempts_valid CHECK (attempts >= 0),
    CONSTRAINT outbox_job_running_locked CHECK ((status = 'running') = (locked_until IS NOT NULL)),
    CONSTRAINT outbox_job_finished_consistent
        CHECK ((status IN ('done', 'failed', 'cancelled')) = (finished_at IS NOT NULL)),
    CONSTRAINT outbox_job_kind_not_blank CHECK (kind <> '')
);

-- [jooq ignore start]
CREATE UNIQUE INDEX outbox_job_open_unique ON jobs.outbox_job (dedupe_key) WHERE status IN ('queued', 'running');
CREATE INDEX outbox_job_due_idx ON jobs.outbox_job (run_after) WHERE status IN ('queued', 'running');
-- [jooq ignore stop]
