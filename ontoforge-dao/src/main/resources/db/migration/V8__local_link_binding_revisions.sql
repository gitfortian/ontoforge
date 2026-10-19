-- M8.1: versioned metadata for an explicitly configured LOCAL_SNAPSHOT link.
-- No LinkFact materialization or implicit association is created by this migration.
-- Source facts remain in the independently authorized PostgreSQL source database.
CREATE TABLE runtime_link_binding (
    link_binding_id UUID PRIMARY KEY,
    package_id TEXT NOT NULL CHECK (length(package_id) BETWEEN 1 AND 200),
    snapshot_id UUID NOT NULL REFERENCES native_schema_snapshot(snapshot_id) ON DELETE RESTRICT,
    link_type_id TEXT NOT NULL CHECK (link_type_id ~ '^[a-z][a-z0-9-]{1,63}$'),
    current_revision BIGINT NOT NULL DEFAULT 0 CHECK (current_revision >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_runtime_link_binding_identity UNIQUE (package_id, snapshot_id, link_type_id)
);

CREATE TABLE runtime_link_binding_revision (
    link_binding_id UUID NOT NULL REFERENCES runtime_link_binding(link_binding_id) ON DELETE RESTRICT,
    revision BIGINT NOT NULL CHECK (revision > 0),
    snapshot_id UUID NOT NULL REFERENCES native_schema_snapshot(snapshot_id) ON DELETE RESTRICT,
    model_payload_sha256 CHAR(64) NOT NULL CHECK (model_payload_sha256 ~ '^[0-9a-f]{64}$'),
    link_type_hash CHAR(64) NOT NULL CHECK (link_type_hash ~ '^[0-9a-f]{64}$'),
    source_binding_id UUID NOT NULL,
    source_binding_revision BIGINT NOT NULL CHECK (source_binding_revision > 0),
    source_binding_digest CHAR(64) NOT NULL CHECK (source_binding_digest ~ '^[0-9a-f]{64}$'),
    target_binding_id UUID NOT NULL,
    target_binding_revision BIGINT NOT NULL CHECK (target_binding_revision > 0),
    target_binding_digest CHAR(64) NOT NULL CHECK (target_binding_digest ~ '^[0-9a-f]{64}$'),
    fk_owner TEXT NOT NULL CHECK (fk_owner IN ('SOURCE','TARGET')),
    fk_source_column TEXT NOT NULL CHECK (fk_source_column ~ '^[a-z][a-z0-9_]{0,62}$'),
    referenced_identity_column TEXT NOT NULL
        CHECK (referenced_identity_column ~ '^[a-z][a-z0-9_]{0,62}$'),
    source_schema_fingerprint CHAR(64) NOT NULL CHECK (source_schema_fingerprint ~ '^[0-9a-f]{64}$'),
    target_schema_fingerprint CHAR(64) NOT NULL CHECK (target_schema_fingerprint ~ '^[0-9a-f]{64}$'),
    validation_scope JSONB NOT NULL CHECK (jsonb_typeof(validation_scope) = 'object'
        AND validation_scope ->> 'coverage' = 'FULL'),
    validation_quality JSONB NOT NULL CHECK (jsonb_typeof(validation_quality) = 'object'),
    validation_status TEXT NOT NULL CHECK (validation_status IN ('VALID','VALID_WITH_ORPHANS')),
    checked_at TIMESTAMPTZ NOT NULL,
    content_digest CHAR(64) NOT NULL CHECK (content_digest ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (link_binding_id, revision),
    CONSTRAINT fk_link_revision_source_binding
        FOREIGN KEY (source_binding_id, source_binding_revision)
        REFERENCES runtime_binding_revision(binding_id, revision) ON DELETE RESTRICT,
    CONSTRAINT fk_link_revision_target_binding
        FOREIGN KEY (target_binding_id, target_binding_revision)
        REFERENCES runtime_binding_revision(binding_id, revision) ON DELETE RESTRICT
);

ALTER TABLE runtime_link_binding
    ADD CONSTRAINT fk_runtime_link_binding_current_revision
    FOREIGN KEY (link_binding_id, current_revision)
    REFERENCES runtime_link_binding_revision(link_binding_id, revision)
    DEFERRABLE INITIALLY DEFERRED;

-- Idempotency evidence lives in the same metadata DB transaction as revision + head.
-- Uniqueness is scoped to a package and head: callers cannot recover by key across packages.
CREATE TABLE runtime_link_binding_operation (
    package_id TEXT NOT NULL,
    link_binding_id UUID NOT NULL REFERENCES runtime_link_binding(link_binding_id) ON DELETE RESTRICT,
    idempotency_key UUID NOT NULL,
    operation_type TEXT NOT NULL CHECK (operation_type IN ('CREATE','UPDATE')),
    request_digest CHAR(64) NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    committed_revision BIGINT NOT NULL CHECK (committed_revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (package_id, idempotency_key),
    CONSTRAINT fk_link_operation_revision
        FOREIGN KEY (link_binding_id, committed_revision)
        REFERENCES runtime_link_binding_revision(link_binding_id, revision) ON DELETE RESTRICT
);

CREATE INDEX ix_runtime_link_binding_revision_endpoints
    ON runtime_link_binding_revision(source_binding_id, source_binding_revision,
                                     target_binding_id, target_binding_revision);
CREATE INDEX ix_runtime_link_binding_snapshot
    ON runtime_link_binding(package_id, snapshot_id);

CREATE OR REPLACE FUNCTION reject_runtime_link_revision_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'runtime_link_binding_revision rows are immutable';
END;
$$;

CREATE TRIGGER trg_runtime_link_binding_revision_immutable
BEFORE UPDATE OR DELETE ON runtime_link_binding_revision
FOR EACH ROW EXECUTE FUNCTION reject_runtime_link_revision_mutation();

CREATE OR REPLACE FUNCTION reject_runtime_link_operation_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'runtime_link_binding_operation rows are immutable';
END;
$$;

CREATE TRIGGER trg_runtime_link_binding_operation_immutable
BEFORE UPDATE OR DELETE ON runtime_link_binding_operation
FOR EACH ROW EXECUTE FUNCTION reject_runtime_link_operation_mutation();
